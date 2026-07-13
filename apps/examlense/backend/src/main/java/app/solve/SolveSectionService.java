package app.solve;

import app.ai.AiProvider;
import app.ai.AiProviderFactory;
import app.ai.SolverStrategies;
import app.api.Access;
import app.persistence.entity.Exam;
import app.persistence.entity.Section;
import app.persistence.entity.Task;
import app.persistence.entity.TaskAnswer;
import app.persistence.repository.SectionRepository;
import app.persistence.repository.TaskRepository;
import app.prompts.Prompts;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Port of supabase/functions/solve-section/index.ts. Solves every task in
 * one section with a single AI call (with one retry for missing answers).
 *
 * Honors the per-section solve lock (`sections.solve_started_at`) so two
 * concurrent invocations cannot race on the same section.
 */
@Service
public class SolveSectionService {

    /**
     * A solve makes up to four provider calls (2 attempts × first pass +
     * missing-answer pass), each of which can run for minutes on large
     * sections — the TTL must comfortably exceed that worst case, or the
     * exam-level sweep steals the lock from a slow-but-alive solve and two
     * runs race on the same section. Locks are released in a finally block,
     * so the TTL only matters after a hard crash.
     */
    private static final long SOLVE_LOCK_TTL_SECONDS = 15 * 60;

    private final SectionRepository sectionRepository;
    private final TaskRepository taskRepository;
    private final AiProviderFactory providerFactory;
    private final Access access;
    private final SolveCore core;

    public SolveSectionService(
        SectionRepository sectionRepository,
        TaskRepository taskRepository,
        AiProviderFactory providerFactory,
        Access access,
        SolveCore core
    ) {
        this.sectionRepository = sectionRepository;
        this.taskRepository = taskRepository;
        this.providerFactory = providerFactory;
        this.access = access;
        this.core = core;
    }

    public record Result(String status, int requested, int answered) {}

    public Result solve(String examId, String sectionId, String userId) {
        UUID examUuid = Access.id(examId);
        UUID sectionUuid = sectionId == null ? null : Access.id(sectionId);

        Exam exam = access.requireExam(examUuid, userId);

        // Solve lock for real sections (unassigned bucket is single-shot).
        if (sectionUuid != null) {
            OffsetDateTime now = OffsetDateTime.now();
            OffsetDateTime cutoff = now.minusSeconds(SOLVE_LOCK_TTL_SECONDS);
            int locked = sectionRepository.acquireSolveLock(sectionUuid, examUuid, now, cutoff);
            if (locked == 0) {
                return new Result("already_running", 0, 0);
            }
        }

        try {
            return doSolve(examUuid, sectionUuid, exam);
        } finally {
            releaseLock(sectionUuid);
        }
    }

    private Result doSolve(UUID examId, UUID sectionId, Exam exam) {
        SolveCore.PromptContext ctx = core.loadContext(examId, sectionId);

        // Tasks for this section (or unassigned bucket), ordered by position.
        List<Task> taskEntities = sectionId != null
            ? taskRepository.findByExamIdAndSectionIdOrderByPositionAsc(examId, sectionId)
            : taskRepository.findByExamIdAndSectionIdIsNullOrderByPositionAsc(examId);
        List<Prompts.TaskPromptInfo> tasks = new ArrayList<>();
        for (Task t : taskEntities) tasks.add(core.toTaskInfo(t));
        if (tasks.isEmpty()) {
            return new Result("no_tasks", 0, 0);
        }

        AiProvider provider = providerFactory.forSolver(SolverStrategies.resolve(exam.getSolverModel()));
        String systemPrompt = core.systemPrompt(exam);

        // First pass: ask for everything.
        SolveCore.AskResult first = core.askForAnswers(provider, systemPrompt, ctx, tasks, null);
        List<Map<String, Object>> allAnswers = new ArrayList<>(first.answers());
        String model = first.model();

        // Second pass: re-ask only for omitted task ids.
        Set<String> answeredIds = new HashSet<>();
        for (Map<String, Object> a : allAnswers) answeredIds.add((String) a.get("task_id"));
        List<Prompts.TaskPromptInfo> missing = new ArrayList<>();
        for (Prompts.TaskPromptInfo t : tasks) if (!answeredIds.contains(t.id())) missing.add(t);
        if (!missing.isEmpty()) {
            try {
                SolveCore.AskResult retry = core.askForAnswers(
                    provider, systemPrompt, ctx, missing,
                    "IMPORTANT: You omitted answers for " + missing.size()
                        + " task(s) previously. Provide an answer for every listed task id this time."
                );
                Set<String> wanted = new HashSet<>();
                for (Prompts.TaskPromptInfo t : missing) wanted.add(t.id());
                for (Map<String, Object> a : retry.answers()) {
                    String id = (String) a.get("task_id");
                    if (wanted.contains(id) && !answeredIds.contains(id)) {
                        allAnswers.add(a);
                        answeredIds.add(id);
                    }
                }
                if (model == null || model.isEmpty()) model = retry.model();
            } catch (RuntimeException ignored) {
                // fall through; persist what we have
            }
        }

        // If the user unconfirmed the section while we were solving, drop on the floor.
        if (sectionId != null) {
            Section confirm = sectionRepository.findById(sectionId).orElse(null);
            if (confirm == null || confirm.getConfirmedAt() == null) {
                return new Result("section_unconfirmed", tasks.size(), 0);
            }
        }

        Map<String, Prompts.TaskPromptInfo> byId = new HashMap<>();
        for (Prompts.TaskPromptInfo t : tasks) byId.put(t.id(), t);

        List<TaskAnswer> rows = new ArrayList<>();
        for (Map<String, Object> a : allAnswers) {
            Prompts.TaskPromptInfo task = byId.get((String) a.get("task_id"));
            if (task == null) continue;
            rows.add(core.toAnswerRow(task, examId, a, provider.name(), model));
        }

        core.replaceAnswers(examId, rows);
        return new Result("ok", tasks.size(), rows.size());
    }

    private void releaseLock(UUID sectionId) {
        if (sectionId == null) return;
        try {
            sectionRepository.releaseSolveLock(sectionId);
        } catch (RuntimeException ignored) {}
    }
}
