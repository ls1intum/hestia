package de.tum.cit.hestia.assessment.sse;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import de.tum.cit.hestia.core.OrgContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SSE hub is the realtime backbone (parse/solve progress, list fan-out).
 * Two properties keep it robust: events reach only the right topic, and a single
 * dead emitter (closed tab / broken pipe) is evicted without disrupting delivery
 * to the healthy subscribers on the same topic.
 *
 * We can't introspect the event name off Spring's builder, so we assert on the
 * number of {@code send()} calls each subscriber receives — enough to pin
 * topic routing and dead-emitter isolation.
 */
class SseHubTest {

    /** The organisation the publishing thread carries. */
    private static final UUID ORG = UUID.randomUUID();
    /** The creator of every exam in these tests. */
    private static final UUID OWNER = UUID.randomUUID();

    @BeforeEach
    void bindOrganisation() {
        OrgContext.set(ORG);
    }

    @AfterEach
    void unbindOrganisation() {
        OrgContext.set(null);
    }

    private static SseHub hub() {
        return new SseHub(examId -> java.util.Optional.of(OWNER));
    }

    private static String listTopic(UUID user) {
        return SseHub.listTopic(user);
    }

    @Test
    void registerReturnsAnEmitter() {
        assertThat(hub().register(listTopic(OWNER))).isNotNull();
    }

    @Test
    void examUpdatedFansOutToBothTheExaminationTopicAndTheListTopic() {
        SseHub hub = hub();
        UUID examId = UUID.randomUUID();
        CountingEmitter examSub = attach(hub, "exam:" + examId);
        CountingEmitter listSub = attach(hub, listTopic(OWNER));

        hub.examUpdated(examId);

        assertThat(examSub.sends.get()).isEqualTo(1);
        assertThat(listSub.sends.get()).isEqualTo(1);
    }

    @Test
    void progressReachesBothTheExaminationTopicAndTheListTopic() {
        // Solve progress must also refresh the dashboard list so the "Solving
        // task X of Y…" bar advances live there, not just on the per-exam splash.
        SseHub hub = hub();
        UUID examId = UUID.randomUUID();
        CountingEmitter examSub = attach(hub, "exam:" + examId);
        CountingEmitter listSub = attach(hub, listTopic(OWNER));

        hub.progress(examId);

        assertThat(examSub.sends.get()).isEqualTo(1);
        assertThat(listSub.sends.get()).isEqualTo(1);
    }

    /**
     * The list stream is per user: only the exam's creator hears of it, not other users of the
     * same university (who may not be allowed to open it).
     */
    @Test
    void listEventsReachOnlyTheCreatorsStream() {
        UUID alice = OWNER;
        UUID bob = UUID.randomUUID();
        SseHub hub = hub();
        CountingEmitter aliceList = attach(hub, listTopic(alice));
        CountingEmitter bobList = attach(hub, listTopic(bob));

        hub.examUpdated(UUID.randomUUID());
        hub.progress(UUID.randomUUID());

        assertThat(aliceList.sends.get()).isEqualTo(2);
        assertThat(bobList.sends.get()).isZero();
    }

    /** An exam without a creator reaches no list stream. */
    @Test
    void anExamWithoutCreatorReachesNoList() {
        SseHub hub = new SseHub(examId -> java.util.Optional.empty());
        CountingEmitter orgList = attach(hub, listTopic(ORG));
        CountingEmitter ownerList = attach(hub, listTopic(OWNER));

        hub.examUpdated(UUID.randomUUID());

        assertThat(orgList.sends.get()).isZero();
        assertThat(ownerList.sends.get()).isZero();
    }

    /** A failing creator lookup is swallowed; the exam topic still gets its event. */
    @Test
    void aFailingCreatorLookupDoesNotBreakThePublish() {
        SseHub hub = new SseHub(examId -> { throw new IllegalStateException("db down"); });
        UUID examId = UUID.randomUUID();
        CountingEmitter examSub = attach(hub, "exam:" + examId);

        hub.examUpdated(examId);

        assertThat(examSub.sends.get()).isEqualTo(1);
    }

    /** Nobody subscribed to a shared topic should ever receive anything. */
    @Test
    void nothingIsPublishedToAnUnscopedSharedTopic() {
        SseHub hub = hub();
        CountingEmitter legacyShared = attach(hub, "exams");

        hub.examUpdated(UUID.randomUUID());
        hub.progress(UUID.randomUUID());

        assertThat(legacyShared.sends.get()).isZero();
    }

    /**
     * A thread without an organisation (it forgot to carry it) publishes to no list;
     * every publish path is documented as never throwing.
     */
    @Test
    void aThreadWithoutOrganisationDoesNotBreakThePublish() {
        SseHub hub = hub();
        OrgContext.set(null);
        CountingEmitter listSub = attach(hub, listTopic(OWNER));

        hub.examUpdated(UUID.randomUUID());

        assertThat(listSub.sends.get()).isZero();
    }

    @Test
    void publishToATopicWithNoSubscribersIsANoOp() {
        SseHub hub = hub();
        // No one subscribed — must not throw.
        hub.examUpdated(UUID.randomUUID());
    }

    @Test
    void aDeadEmitterIsEvictedAndDoesNotBlockDeliveryToHealthyOnes() {
        SseHub hub = hub();
        UUID examId = UUID.randomUUID();
        String topic = "exam:" + examId;
        attachRaw(hub, topic, new ThrowingEmitter()); // send() throws — simulates a dropped client
        CountingEmitter healthy = attach(hub, topic);

        hub.tasksUpdated(examId); // the dead emitter's IOException must be swallowed

        assertThat(healthy.sends.get()).isEqualTo(1);

        // The dead emitter was evicted, so a second publish still reaches the healthy one.
        hub.tasksUpdated(examId);
        assertThat(healthy.sends.get()).isEqualTo(2);
    }

    // --- helpers ---

    private CountingEmitter attach(SseHub hub, String topic) {
        CountingEmitter emitter = new CountingEmitter();
        attachRaw(hub, topic, emitter);
        return emitter;
    }

    @SuppressWarnings("unchecked")
    private void attachRaw(SseHub hub, String topic, SseEmitter emitter) {
        try {
            var field = SseHub.class.getDeclaredField("topics");
            field.setAccessible(true);
            Map<String, Set<SseEmitter>> topics = (Map<String, Set<SseEmitter>>) field.get(hub);
            topics.computeIfAbsent(topic, k -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(emitter);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    static class CountingEmitter extends SseEmitter {
        final AtomicInteger sends = new AtomicInteger();

        @Override
        public void send(SseEmitter.SseEventBuilder builder) throws IOException {
            sends.incrementAndGet();
        }
    }

    static class ThrowingEmitter extends SseEmitter {
        @Override
        public void send(SseEmitter.SseEventBuilder builder) throws IOException {
            throw new IOException("broken pipe");
        }
    }
}
