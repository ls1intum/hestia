package app.parse;

import app.ai.ParserStrategy;
import app.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
@Tag(name = "Parsing", description = "Turn an uploaded exam PDF into sections, tasks, and figures.")
public class ParseExamController {

    public record ParseExamRequest(
        @NotBlank String exam_id,
        @NotBlank String storage_path,
        String parser_model
    ) {}

    private final ParseExamService service;

    public ParseExamController(ParseExamService service) {
        this.service = service;
    }

    /**
     * POST /api/parse-exam-pdf  { "exam_id": "...", "storage_path": "...", ... }
     *
     * Verifies ownership synchronously, dispatches the extraction onto the
     * background pool, and returns 202 immediately. The frontend observes
     * `exams.parse_phase` for progress (via SSE once Phase 2c lands).
     */
    @Operation(
        summary = "Parse an uploaded exam PDF",
        description = """
            Verifies ownership synchronously, then runs the extraction on a background pool \
            and returns **202** right away with `{ ok, exam_id, status: "parsing" }`.

            `parser_model` is optional; an omitted **or unrecognised** id falls back to the \
            default parser rather than failing, so check the exam's `parser_model` afterwards \
            to see what actually served.

            `storage_path` is the value returned by `POST /api/exams/{examId}/pdf`. The \
            parse outcome arrives over `GET /api/exams/{id}/events`, not in this response: \
            on success the exam moves to `draft`, on failure to `failed` with `parse_error` \
            set. A transient provider failure is retried once with the fallback model, which \
            may change the exam's `parser_model`.""")
    @ApiResponse(responseCode = "202", description = "Parse accepted and dispatched.")
    @ApiResponse(responseCode = "400", description = "`exam_id` or `storage_path` is blank.")
    @ApiResponse(responseCode = "403", description = "The exam belongs to another owner.")
    @ApiResponse(responseCode = "404", description = "No such exam, or `exam_id` is not a valid UUID.")
    @PostMapping("/parse-exam-pdf")
    public ResponseEntity<Map<String, Object>> parse(
        @Valid @RequestBody ParseExamRequest req,
        @CurrentUser String userId
    ) {
        // Start the clock here (not inside run()) so the recorded duration tracks
        // the closest-to-e2e server-side window: preflight + queue wait + pipeline.
        long requestNanos = System.nanoTime();
        ParserStrategy strategy = service.preflight(req.exam_id(), userId, req.parser_model());
        service.runAsync(
            req.exam_id(),
            userId,
            req.storage_path(),
            strategy,
            requestNanos
        );
        return ResponseEntity.accepted().body(Map.of(
            "ok", true,
            "exam_id", req.exam_id(),
            "status", "parsing"
        ));
    }
}
