package app.sse;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * In-process Server-Sent-Events hub — the replacement for Supabase realtime.
 * Emitters are grouped by topic; the parse/solve services publish to them as
 * background work changes exam status or writes answers.
 *
 * Topics:
 *   exam:{examId}  — status/phase changes + evaluation progress for one exam
 *   exams          — list-level fan-out (any owned exam changed)
 *
 * TODO(auth): when real multi-user auth lands, scope the `exams` topic per
 * user (`exams:{userId}`) — today every authenticated client shares it.
 *
 * All publish-side methods are guaranteed never to throw: a dead client socket
 * is evicted and logged at debug, so callers don't need defensive try/catch.
 */
@Component
public class SseHub {

    private static final Logger log = LoggerFactory.getLogger(SseHub.class);

    private final Map<String, Set<SseEmitter>> topics = new ConcurrentHashMap<>();

    public SseEmitter register(String topic) {
        SseEmitter emitter = new SseEmitter(0L); // no timeout — long-lived stream
        topics.computeIfAbsent(topic, k -> ConcurrentHashMap.newKeySet()).add(emitter);
        emitter.onCompletion(() -> remove(topic, emitter));
        emitter.onTimeout(() -> remove(topic, emitter));
        emitter.onError(e -> remove(topic, emitter));
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException ignored) {
            remove(topic, emitter);
        }
        return emitter;
    }

    /**
     * Keep-alive comment every 25s: stops idle-connection teardown by proxies
     * and detects dead clients even on quiet topics (a failed write evicts the
     * emitter, which otherwise only happens on the next real publish).
     */
    @Scheduled(fixedDelay = 25_000)
    void heartbeat() {
        for (String topic : topics.keySet()) {
            Set<SseEmitter> set = topics.get(topic);
            if (set == null) continue;
            for (SseEmitter emitter : set) {
                try {
                    emitter.send(SseEmitter.event().comment("keep-alive"));
                } catch (Exception e) {
                    drop(topic, emitter, e);
                }
            }
        }
    }

    private void remove(String topic, SseEmitter emitter) {
        // Drop the whole topic entry once its last subscriber leaves so the
        // map doesn't accumulate empty sets for every exam ever watched.
        topics.computeIfPresent(topic, (k, set) -> {
            set.remove(emitter);
            return set.isEmpty() ? null : set;
        });
    }

    private void publish(String topic, String event, Object data) {
        Set<SseEmitter> set = topics.get(topic);
        if (set == null) return;
        for (SseEmitter emitter : set) {
            try {
                emitter.send(SseEmitter.event().name(event).data(data));
            } catch (Exception e) {
                drop(topic, emitter, e);
            }
        }
    }

    /**
     * Client went away mid-stream (navigated off, closed tab, network drop) —
     * writing to the dead socket throws "Broken pipe". Expected teardown, not an
     * error: evict + complete the emitter and log at debug so it stays quiet.
     */
    private void drop(String topic, SseEmitter emitter, Exception cause) {
        remove(topic, emitter);
        try { emitter.complete(); } catch (Exception ignored) { /* already dead */ }
        log.debug("SSE emitter for topic {} dropped: {}", topic, cause.getMessage());
    }

    /** Exam status/phase changed — notify that exam's subscribers and the list. */
    public void examUpdated(UUID examId) {
        Map<String, Object> data = Map.of("exam_id", examId.toString());
        publish("exam:" + examId, "exam", data);
        publish("exams", "exam", data);
    }

    /**
     * A task answer was written — notify the exam's progress subscribers, and
     * also refresh the dashboard list so solve progress ("Solving task X of Y…")
     * advances live there too, not just on the per-exam splash.
     */
    public void progress(UUID examId) {
        Map<String, Object> data = Map.of("exam_id", examId.toString());
        publish("exam:" + examId, "progress", data);
        publish("exams", "exam", data);
    }

    /** Task rows changed server-side (e.g. learning goals were generated). */
    public void tasksUpdated(UUID examId) {
        publish("exam:" + examId, "tasks", Map.of("exam_id", examId.toString()));
    }
}
