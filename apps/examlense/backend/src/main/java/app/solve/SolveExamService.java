package app.solve;

import app.ai.AiExceptions;
import app.ai.SolverStrategies;
import app.shared.Access;
import app.exam.Exam;
import app.task.Task;
import app.task.TaskAnswer;
import app.exam.ExamRepository;
import app.task.TaskAnswerRepository;
import app.grading.TaskGradeRepository;
import app.task.TaskRepository;
import app.sse.SseHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

/**
 * Port of supabase/functions/solve-exam/index.ts. Orchestrates per-section
 * solves with a small concurrency budget so we stay under the AI gateway's
 * per-trace rate window.
 *
 * The HTTP request returns immediately with the dispatch plan; actual work
 * happens on the {@code solverExecutor} pool.
 */
@Service
public class SolveExamService {

    private static final Logger log = LoggerFactory.getLogger(SolveExamService.class);

    private static final int MAX_CONCURRENT = 2;
    private static final int MAX_REQUEUE_ATTEMPTS = 3;

    private final ExamRepository examRepository;
    private final TaskRepository taskRepository;
    private final TaskAnswerRepository taskAnswerRepository;
    private final TaskGradeRepository taskGradeRepository;
    private final SolveSectionService sectionService;
    private final Executor solverExecutor;
    private final Access access;
    private final SseHub sse;

    public SolveExamService(
        ExamRepository examRepository,
        TaskRepository taskRepository,
        TaskAnswerRepository taskAnswerRepository,
        TaskGradeRepository taskGradeRepository,
        SolveSectionService sectionService,
        @Qualifier("solverExecutor") Executor solverExecutor,
        Access access,
        SseHub sse
    ) {
        this.examRepository = examRepository;
        this.taskRepository = taskRepository;
        this.taskAnswerRepository = taskAnswerRepository;
        this.taskGradeRepository = taskGradeRepository;
        this.sectionService = sectionService;
        this.solverExecutor = solverExecutor;
        this.access = access;
        this.sse = sse;
    }

    public record DispatchPlan(int sections, int tasks) {}

    /**
     * Synchronous prep + ownership check. After the row resets the orchestrator
     * is kicked off asynchronously and this method returns the plan to the
     * caller, mirroring the edge function's "return early, keep working"
     * behavior.
     */
    public DispatchPlan startEvaluation(String examId, String userId) {
        UUID examUuid = Access.id(examId);
        Exam exam = access.requireExam(examUuid, userId);

        // Reset previous answers + auto grades so progress starts at 0/N.
        taskAnswerRepository.deleteByExamId(examUuid);
        taskGradeRepository.deleteByExamIdAndAutoGradedTrue(examUuid);

        List<Task> taskRows = taskRepository.findByExamIdOrderByPositionAsc(examUuid);
        int totalTasks = taskRows.size();
        if (totalTasks == 0) {
            examRepository.updateStatus(examUuid, "grading");
            sse.examUpdated(examUuid);
            return new DispatchPlan(0, 0);
        }

        // Build dispatch buckets in stable order.
        List<String> sectionIds = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        boolean hasUnassigned = false;
        for (Task t : taskRows) {
            UUID sid = t.getSectionId();
            if (sid == null) hasUnassigned = true;
            else if (seen.add(sid.toString())) sectionIds.add(sid.toString());
        }
        List<String> bucketSectionIds = new ArrayList<>(sectionIds);
        if (hasUnassigned) bucketSectionIds.add(null);

        String solverModel = exam.getSolverModel() != null ? exam.getSolverModel() : SolverStrategies.DEFAULT_ID;
        examRepository.startEvaluating(examUuid, solverModel);
        sse.examUpdated(examUuid);

        // Hand off to the orchestrator and return immediately. Submitted to the
        // executor explicitly — a self-invoked @Async method bypasses Spring's
        // proxy and would run the whole solve on this request thread.
        CompletableFuture.runAsync(
            () -> dispatch(examId, userId, bucketSectionIds, totalTasks, taskRows),
            solverExecutor
        );

        return new DispatchPlan(bucketSectionIds.size(), totalTasks);
    }

    private void dispatch(
        String examId, String userId,
        List<String> sectionIds, int totalTasks,
        List<Task> allTaskRows
    ) {
        try {
            runDispatch(examId, userId, sectionIds, totalTasks, allTaskRows);
        } catch (Exception e) {
            log.error("solve-exam dispatch crashed for {}", examId, e);
            try {
                UUID examUuid = Access.id(examId);
                examRepository.markSolveFailed(examUuid, e.getMessage());
                sse.examUpdated(examUuid);
            } catch (Exception ignored) {}
        }
    }

    private void runDispatch(
        String examId, String userId,
        List<String> sectionIds, int totalTasks,
        List<Task> allTaskRows
    ) throws InterruptedException {
        UUID examUuid = Access.id(examId);
        Semaphore slots = new Semaphore(MAX_CONCURRENT);

        joinAll(dispatchAll(slots, examId, userId, sectionIds));

        // Verify completion: some sections may have returned empty or partial
        // answer arrays.
        long answered = taskAnswerRepository.countByExamId(examUuid);
        if (answered >= totalTasks) {
            // Compare-and-set: don't flip a since-cancelled exam back out of `failed`.
            examRepository.updateStatusIfCurrent(examUuid, "evaluating", "grading");
            sse.examUpdated(examUuid);
            return;
        }

        // One targeted sweep: redispatch sections that still have missing answers.
        Set<UUID> answeredIds = new HashSet<>();
        for (TaskAnswer a : taskAnswerRepository.findByExamId(examUuid)) {
            answeredIds.add(a.getTaskId());
        }
        Set<String> missingSections = new LinkedHashSet<>();
        boolean missingUnassigned = false;
        for (Task t : allTaskRows) {
            if (!answeredIds.contains(t.getId())) {
                UUID sid = t.getSectionId();
                if (sid == null) missingUnassigned = true;
                else missingSections.add(sid.toString());
            }
        }
        List<String> sweepIds = new ArrayList<>(missingSections);
        if (missingUnassigned) sweepIds.add(null);
        if (sweepIds.isEmpty()) return;

        joinAll(dispatchAll(slots, examId, userId, sweepIds));

        long finalCount = taskAnswerRepository.countByExamId(examUuid);
        if (finalCount >= totalTasks) {
            // Compare-and-set: don't flip a since-cancelled exam back out of `failed`.
            examRepository.updateStatusIfCurrent(examUuid, "evaluating", "grading");
        } else {
            // markSolveFailed is itself guarded on status='evaluating', so a user
            // cancel keeps its own message instead of "Evaluation incomplete".
            examRepository.markSolveFailed(examUuid,
                "Evaluation incomplete: " + finalCount + "/" + totalTasks + " tasks answered");
        }
        sse.examUpdated(examUuid);
    }

    /**
     * Submit one solve per section, acquiring a concurrency permit BEFORE
     * handing the work to the pool. This keeps at most {@link #MAX_CONCURRENT}
     * pool threads busy — queued sections wait here on the dispatcher thread
     * instead of each hoarding a pool thread blocked on the semaphore (the
     * pool is shared with parse jobs).
     */
    private List<CompletableFuture<Void>> dispatchAll(
        Semaphore slots, String examId, String userId, List<String> sectionIds
    ) throws InterruptedException {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (String sid : sectionIds) {
            slots.acquire();
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    solveWithRetry(examId, userId, sid);
                } finally {
                    slots.release();
                }
            }, solverExecutor));
        }
        return futures;
    }

    /** Solve one section, retrying transient failures with exponential backoff. */
    private void solveWithRetry(String examId, String userId, String sectionId) {
        String label = sectionId == null ? "_unassigned" : sectionId;
        for (int attempt = 0; ; attempt++) {
            try {
                sectionService.solve(examId, sectionId, userId);
                return;
            } catch (RuntimeException e) {
                if (attempt >= MAX_REQUEUE_ATTEMPTS || !AiExceptions.isTransient(e)) {
                    log.error("solve-section {} failed permanently: {}", label, e.getMessage());
                    return;
                }
                long backoff = 500L * (1L << attempt);
                log.warn("solve-section {} -> retry in {}ms (attempt {}): {}",
                    label, backoff, attempt + 1, e.getMessage());
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static void joinAll(List<CompletableFuture<Void>> futures) {
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }
}
