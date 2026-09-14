package app.sse;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** Reproduction scratchpad for the broken-pipe ERROR seen during a parse POST. */
class SseHubBrokenPipeTest {

    /** An emitter whose socket has already gone away. */
    private static class DeadEmitter extends SseEmitter {
        final AtomicInteger sends = new AtomicInteger();

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            sends.incrementAndGet();
            throw new IOException("Broken pipe");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Set<SseEmitter>> topicsOf(SseHub hub) throws Exception {
        Field f = SseHub.class.getDeclaredField("topics");
        f.setAccessible(true);
        return (Map<String, Set<SseEmitter>>) f.get(hub);
    }

    @Test
    void publishDoesNotLetABrokenPipeEscapeToTheCaller() throws Exception {
        SseHub hub = new SseHub(mock(app.exam.ExamRepository.class));
        UUID examId = UUID.randomUUID();
        DeadEmitter dead = new DeadEmitter();
        Map<String, Set<SseEmitter>> topics = topicsOf(hub);
        // The per-exam topic, which publishes unconditionally — the list topic is
        // resolved per owner and would need the exam to still exist.
        String topic = "exam:" + examId;
        topics.computeIfAbsent(topic, k -> ConcurrentHashMap.newKeySet()).add(dead);

        assertThatCode(() -> hub.examUpdated(examId)).doesNotThrowAnyException();

        assertThat(dead.sends.get()).isEqualTo(1);
        // And the dead emitter should have been evicted, not left to fail again.
        assertThat(topics.get(topic)).isNull();
    }
}
