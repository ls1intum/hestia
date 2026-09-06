package app.sse;

import app.shared.Access;
import app.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Realtime", description = """
    Server-sent event streams. These are long-lived `text/event-stream` responses, not \
    request/response calls — "Try it out" in Swagger UI will hang open rather than return. \
    Because `EventSource` cannot set headers, both accept the bearer token as a `token` \
    query parameter instead. The hub emits a keep-alive comment every 25s so proxies don't \
    drop an idle stream.""")
public class SseController {

    private final SseHub hub;
    private final Access access;

    public SseController(SseHub hub, Access access) {
        this.hub = hub;
        this.access = access;
    }

    /** Status/phase + progress events for one exam (replaces exam-${id} and exam-progress-${id}). */
    @Operation(
        summary = "Subscribe to one exam's events",
        description = """
            Emits three named events, each with an `{ "exam_id": "<uuid>" }` payload:

            - `exam` — the exam's status or parse phase changed
            - `progress` — a task answer was written (solve progress advanced)
            - `tasks` — task rows changed server-side, e.g. learning goals finished generating

            The payload carries no state; treat each event as a signal to refetch.""")
    @ApiResponse(responseCode = "200", description = "Event stream opened.",
        content = @Content(mediaType = "text/event-stream"))
    @ApiResponse(responseCode = "403", description = "The exam belongs to another owner.")
    @ApiResponse(responseCode = "404", description = "No such exam, or `examId` is not a valid UUID.")
    @GetMapping("/exams/{examId}/events")
    public SseEmitter examEvents(@PathVariable String examId, @CurrentUser String userId) {
        access.requireExam(Access.id(examId), userId);
        return hub.register("exam:" + examId);
    }

    /** List-level events (replaces exams-${userId}). */
    @Operation(
        summary = "Subscribe to exam-list events",
        description = """
            Dashboard-level stream. Emits a single `exam` event with an \
            `{ "exam_id": "<uuid>" }` payload whenever any exam changes status or makes solve \
            progress, so the list can refresh without one subscription per row.""")
    @ApiResponse(responseCode = "200", description = "Event stream opened.",
        content = @Content(mediaType = "text/event-stream"))
    @GetMapping("/exams/events")
    public SseEmitter listEvents(@CurrentUser String userId) {
        return hub.register("exams");
    }
}
