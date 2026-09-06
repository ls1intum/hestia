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

import java.util.Map;

@RestController
@RequestMapping("/api")
@Tag(name = "Solving", description = "Dispatch the AI solver over a whole exam, one section, or one task.")
public class SolveExamController {

    public record SolveExamRequest(@NotBlank String exam_id) {}

    private final SolveExamService service;

    public SolveExamController(SolveExamService service) { this.service = service; }

    /**
     * POST /api/solve-exam  { "exam_id": "..." }
     *
     * Returns the dispatch plan immediately. Section solves continue on the
     * background pool; progress is observed via SSE (Phase 2c) once wired.
     */
    @Operation(
        summary = "Solve a whole exam",
        description = """
            Moves the exam to `evaluating` and dispatches every section onto the background \
            solver pool. Returns the dispatch plan `{ ok, sections, tasks }` immediately — \
            the work itself is not finished when this responds. Watch \
            `GET /api/exams/{id}/events` for progress.

            An exam that is already evaluating, or that has no tasks, is a no-op: the \
            response is still 200, with `sections` and `tasks` both 0.""")
    @ApiResponse(responseCode = "400", description = "`exam_id` is blank.")
    @ApiResponse(responseCode = "403", description = "The exam belongs to another owner.")
    @ApiResponse(responseCode = "404", description = "No such exam, or `exam_id` is not a valid UUID.")
    @PostMapping("/solve-exam")
    public Map<String, Object> solve(@Valid @RequestBody SolveExamRequest req, @CurrentUser String userId) {
        SolveExamService.DispatchPlan plan = service.startEvaluation(req.exam_id(), userId);
        return Map.of(
            "ok", true,
            "sections", plan.sections(),
            "tasks", plan.tasks()
        );
    }
}
