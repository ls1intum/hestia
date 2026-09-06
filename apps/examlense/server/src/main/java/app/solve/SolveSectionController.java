package app.solve;

import app.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
@Tag(name = "Solving", description = "Dispatch the AI solver over a whole exam, one section, or one task.")
public class SolveSectionController {

    public record SolveSectionRequest(@NotBlank String exam_id, String section_id) {}

    private final SolveSectionService service;

    public SolveSectionController(SolveSectionService service) { this.service = service; }

    /**
     * POST /api/solve-section  { "exam_id": "...", "section_id": "..." | null }
     * `section_id: null` solves the unassigned-tasks bucket.
     */
    @Operation(
        summary = "Solve one section",
        description = """
            Answers every task in the section, **synchronously** — the AI calls happen on \
            the request thread, so this can take minutes. A null `section_id` solves the \
            bucket of tasks not assigned to any section.

            Returns `{ ok, status, requested, answered }`. A section already being solved \
            by another caller returns 200 with `status: "already_running"` and no work done.""")
    @ApiResponse(responseCode = "400", description = "`exam_id` is blank.")
    @ApiResponse(responseCode = "402", description = "The AI provider is out of credit.")
    @ApiResponse(responseCode = "403", description = "The exam belongs to another owner.")
    @ApiResponse(responseCode = "404", description = "No such exam or section, or an id is not a valid UUID.")
    @ApiResponse(responseCode = "429", description = "Per-IP rate limit exceeded, or the AI provider throttled us.")
    @ApiResponse(responseCode = "502", description = "The AI provider failed or returned unusable output.")
    @PostMapping("/solve-section")
    public Map<String, Object> solve(@Valid @RequestBody SolveSectionRequest req, @CurrentUser String userId) {
        SolveSectionService.Result result = service.solve(req.exam_id(), req.section_id(), userId);
        Map<String, Object> body = new HashMap<>();
        body.put("ok", true);
        body.put("status", result.status());
        body.put("requested", result.requested());
        body.put("answered", result.answered());
        return body;
    }
}
