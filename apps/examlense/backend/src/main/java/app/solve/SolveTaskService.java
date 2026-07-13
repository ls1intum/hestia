package app.solve;

import app.ai.AiExceptions;
import app.ai.AiProvider;
import app.ai.AiProviderFactory;
import app.ai.SolverStrategies;
import app.api.Access;
import app.error.ApiException;
import app.persistence.entity.Exam;
import app.persistence.entity.Task;
import app.persistence.repository.TaskRepository;
import app.prompts.Prompts;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Port of supabase/functions/solve-task/index.ts. Generates an AI answer for
 * one task and persists it. Structurally this is a section solve restricted
 * to a one-task subset — the shared machinery lives in {@link SolveCore}.
 */
@Service
public class SolveTaskService {

    private final TaskRepository taskRepository;
    private final AiProviderFactory providerFactory;
    private final Access access;
    private final SolveCore core;

    public SolveTaskService(
        TaskRepository taskRepository,
        AiProviderFactory providerFactory,
        Access access,
        SolveCore core
    ) {
        this.taskRepository = taskRepository;
        this.providerFactory = providerFactory;
        this.access = access;
        this.core = core;
    }

    public void solve(String taskId, String userId) {
        Task task = taskRepository.findById(Access.id(taskId))
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Task not found"));
        Exam exam = access.requireExam(task.getExamId(), userId);

        SolveCore.PromptContext ctx = core.loadContext(exam.getId(), task.getSectionId());
        Prompts.TaskPromptInfo taskInfo = core.toTaskInfo(task);

        AiProvider provider = providerFactory.forSolver(SolverStrategies.resolve(exam.getSolverModel()));
        String systemPrompt = core.systemPrompt(exam);

        Answer answer = invokeWithRetry(provider, systemPrompt, ctx, taskInfo);
        core.replaceAnswers(exam.getId(), List.of(
            core.toAnswerRow(taskInfo, exam.getId(), answer.raw(), provider.name(), answer.model())
        ));
    }

    private record Answer(Map<String, Object> raw, String model) {}

    /**
     * Calls the provider with one retry on transient errors AND on the model
     * returning no answer for the requested task id — matches the TS impl.
     */
    private Answer invokeWithRetry(
        AiProvider provider, String systemPrompt,
        SolveCore.PromptContext ctx, Prompts.TaskPromptInfo taskInfo
    ) {
        RuntimeException lastErr = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            String extraInstruction = attempt == 0
                ? null
                : "IMPORTANT: You must include an answer for task id " + taskInfo.id() + " in your tool call.";
            try {
                SolveCore.AskResult res = core.askForAnswers(
                    provider, systemPrompt, ctx, List.of(taskInfo), extraInstruction);
                for (Map<String, Object> a : res.answers()) {
                    if (taskInfo.id().equals(a.get("task_id"))) {
                        return new Answer(a, res.model());
                    }
                }
                lastErr = new RuntimeException("Model did not return an answer for this task");
            } catch (AiExceptions.PaymentRequiredException e) {
                throw e;
            } catch (RuntimeException e) {
                lastErr = e;
                if (!AiExceptions.isTransient(e)) break;
            }
            if (attempt == 0) {
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            }
        }
        if (lastErr instanceof AiExceptions.RateLimitException rate) {
            throw rate;
        }
        throw new ApiException(HttpStatus.BAD_GATEWAY,
            lastErr == null ? "Model failed" : lastErr.getMessage());
    }
}
