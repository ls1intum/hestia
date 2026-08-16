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
public class SolveTaskController {

    public record SolveTaskRequest(@NotBlank String task_id) {}

    private final SolveTaskService service;

    public SolveTaskController(SolveTaskService service) {
        this.service = service;
    }

    /**
     * POST /api/solve-task  { "task_id": "<uuid>" }
     * Single-task solve. Auth is enforced by the security filter chain.
     */
    @PostMapping("/solve-task")
    public Map<String, Object> solve(@Valid @RequestBody SolveTaskRequest req, @CurrentUser String userId) {
        service.solve(req.task_id(), userId);
        return Map.of("ok", true);
    }
}
