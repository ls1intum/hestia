package app.sse;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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

    @Test
    void registerReturnsAnEmitter() {
        assertThat(new SseHub().register("exams")).isNotNull();
    }

    @Test
    void examUpdatedFansOutToBothTheExamTopicAndTheListTopic() {
        SseHub hub = new SseHub();
        UUID examId = UUID.randomUUID();
        CountingEmitter examSub = attach(hub, "exam:" + examId);
        CountingEmitter listSub = attach(hub, "exams");

        hub.examUpdated(examId);

        assertThat(examSub.sends.get()).isEqualTo(1);
        assertThat(listSub.sends.get()).isEqualTo(1);
    }

    @Test
    void progressReachesBothTheExamTopicAndTheListTopic() {
        // Solve progress must also refresh the dashboard list so the "Solving
        // task X of Y…" bar advances live there, not just on the per-exam splash.
        SseHub hub = new SseHub();
        UUID examId = UUID.randomUUID();
        CountingEmitter examSub = attach(hub, "exam:" + examId);
        CountingEmitter listSub = attach(hub, "exams");

        hub.progress(examId);

        assertThat(examSub.sends.get()).isEqualTo(1);
        assertThat(listSub.sends.get()).isEqualTo(1);
    }

    @Test
    void publishToATopicWithNoSubscribersIsANoOp() {
        SseHub hub = new SseHub();
        // No one subscribed — must not throw.
        hub.examUpdated(UUID.randomUUID());
    }

    @Test
    void aDeadEmitterIsEvictedAndDoesNotBlockDeliveryToHealthyOnes() {
        SseHub hub = new SseHub();
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
