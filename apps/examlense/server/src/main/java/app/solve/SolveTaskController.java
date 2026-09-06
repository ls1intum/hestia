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
    @Operation(
        summary = "Solve one task",
        description = """
            Re-answers a single task with the exam's locked solver model, replacing any \
            existing answer. **Synchronous** — the AI call happens on the request thread. \
            Returns `{ ok: true }`.""")
    @ApiResponse(responseCode = "400", description = "`task_id` is blank.")
    @ApiResponse(responseCode = "402", description = "The AI provider is out of credit.")
    @ApiResponse(responseCode = "403", description = "The task's exam belongs to another owner.")
    @ApiResponse(responseCode = "404", description = "No such task, or `task_id` is not a valid UUID.")
    @ApiResponse(responseCode = "429", description = "Per-IP rate limit exceeded, or the AI provider throttled us.")
    @ApiResponse(responseCode = "502", description = "The AI provider failed or returned unusable output.")
    @PostMapping("/solve-task")
    public Map<String, Object> solve(@Valid @RequestBody SolveTaskRequest req, @CurrentUser String userId) {
        service.solve(req.task_id(), userId);
        return Map.of("ok", true);
    }
}
