package app.solve;

import app.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
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
