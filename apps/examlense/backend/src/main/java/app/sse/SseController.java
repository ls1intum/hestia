package app.sse;

import app.api.Access;
import app.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE subscription endpoints (replace the Supabase realtime channels). Note:
 * the browser EventSource API cannot set an Authorization header, so callers
 * pass the token as a {@code ?token=} query param (honored by StaticTokenAuthFilter).
 */
@RestController
@RequestMapping("/api")
public class SseController {

    private final SseHub hub;
    private final Access access;

    public SseController(SseHub hub, Access access) {
        this.hub = hub;
        this.access = access;
    }

    /** Status/phase + progress events for one exam (replaces exam-${id} and exam-progress-${id}). */
    @GetMapping("/exams/{examId}/events")
    public SseEmitter examEvents(@PathVariable String examId, @CurrentUser String userId) {
        access.requireExam(Access.id(examId), userId);
        return hub.register("exam:" + examId);
    }

    /** List-level events (replaces exams-${userId}). */
    @GetMapping("/exams/events")
    public SseEmitter listEvents(@CurrentUser String userId) {
        return hub.register("exams");
    }
}
