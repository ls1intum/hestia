package app.sse;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import app.examination.ExaminationRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    private static final UUID OWNER = UUID.randomUUID();

    /** A hub whose exams all belong to {@link #OWNER}. */
    private static SseHub hub() {
        return hubWithOwner(OWNER);
    }

    private static SseHub hubWithOwner(UUID owner) {
        ExaminationRepository exams = mock(ExaminationRepository.class);
        when(exams.findOwnerIdById(any())).thenReturn(Optional.ofNullable(owner));
        return new SseHub(exams);
    }

    private static String listTopic(UUID owner) {
        return "exams:" + owner;
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
     * The list stream is per owner. A shared one would tell every signed-in client
     * the id of every exam anyone touched, and make them all refetch whenever
     * anyone did anything.
     */
    @Test
    void listEventsReachOnlyTheOwningUsersStream() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        SseHub hub = hubWithOwner(alice); // every exam in this hub belongs to alice
        CountingEmitter aliceList = attach(hub, listTopic(alice));
        CountingEmitter bobList = attach(hub, listTopic(bob));

        hub.examUpdated(UUID.randomUUID());
        hub.progress(UUID.randomUUID());

        assertThat(aliceList.sends.get()).isEqualTo(2);
        assertThat(bobList.sends.get()).isZero();
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
     * Publishes run on background threads after parse/solve work, by which point
     * the exam may have been deleted. Every publish path is documented as never
     * throwing, so a missing owner has to be swallowed.
     */
    @Test
    void aDeletedExaminationDoesNotBreakThePublish() {
        SseHub hub = hubWithOwner(null); // findOwnerIdById returns empty
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
