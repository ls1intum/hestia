package app.solve;

import app.ai.AiExceptions;
import app.ai.SolverStrategies;
import app.shared.Access;
import app.examination.Examination;
import app.examination.EvaluationRun;
import app.examination.EvaluationRunRepository;
import app.taskblock.TaskBlock;
import app.taskblock.AIAnswer;
import app.examination.ExaminationRepository;
import app.taskblock.AIAnswerRepository;
import app.grading.GradeRepository;
import app.taskblock.TaskBlockRepository;
import app.user.LlmQuotaService;
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
public class SolveExaminationService {

    private static final Logger log = LoggerFactory.getLogger(SolveExaminationService.class);

    private static final int MAX_CONCURRENT = 2;
    private static final int MAX_REQUEUE_ATTEMPTS = 3;

    private final ExaminationRepository examRepository;
    private final TaskBlockRepository taskRepository;
    private final AIAnswerRepository taskAnswerRepository;
    private final GradeRepository taskGradeRepository;
    private final EvaluationRunRepository evaluationRunRepository;
    private final SolveSectionService sectionService;
    private final Executor solverExecutor;
    private final Access access;
    private final SseHub sse;
    private final LlmQuotaService quota;

    public SolveExaminationService(
        ExaminationRepository examRepository,
        TaskBlockRepository taskRepository,
        AIAnswerRepository taskAnswerRepository,
        GradeRepository taskGradeRepository,
        EvaluationRunRepository evaluationRunRepository,
        SolveSectionService sectionService,
        @Qualifier("solverExecutor") Executor solverExecutor,
        Access access,
        SseHub sse,
        LlmQuotaService quota
    ) {
        this.examRepository = examRepository;
        this.taskRepository = taskRepository;
        this.taskAnswerRepository = taskAnswerRepository;
        this.taskGradeRepository = taskGradeRepository;
        this.evaluationRunRepository = evaluationRunRepository;
        this.sectionService = sectionService;
        this.solverExecutor = solverExecutor;
        this.access = access;
        this.sse = sse;
        this.quota = quota;
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
        Examination exam = access.requireExamination(examUuid, userId);

        // Refuse an over-quota caller before the resets below touch anything: this
        // method deletes prior answers and auto grades on its way to dispatch, so
        // a late 429 would leave the exam wiped and stuck in `evaluating`. The
        // matching record() happens at the actual dispatch, so a no-op CAS or a
        // task-less exam costs the caller nothing.
        quota.check(userId, LlmQuotaService.KIND_SOLVE);

        // The compare-and-set has to happen BEFORE the destructive resets below, so
        // a stale dispatch bails without wiping answers — see startEvaluating.
        String solverModel = exam.getSolverModel() != null ? exam.getSolverModel() : SolverStrategies.DEFAULT_ID;
        if (examRepository.startEvaluating(examUuid, solverModel) == 0) {
            return new DispatchPlan(0, 0);
        }
        sse.examUpdated(examUuid);

        // Reset previous answers + auto grades so progress starts at 0/N.
        taskAnswerRepository.deleteByExamId(examUuid);
        taskGradeRepository.deleteByExamIdAndAutoGradedTrue(examUuid);
        recordRun(exam, solverModel);

        List<TaskBlock> taskRows = taskRepository.findByExamIdOrderByPositionAsc(examUuid);
        int totalTaskBlocks = taskRows.size();
        if (totalTaskBlocks == 0) {
            examRepository.updateStatus(examUuid, "grading");
            sse.examUpdated(examUuid);
            return new DispatchPlan(0, 0);
        }

        // Build dispatch buckets in stable order.
        List<String> sectionIds = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        boolean hasUnassigned = false;
        for (TaskBlock t : taskRows) {
            UUID sid = t.getSectionId();
            if (sid == null) hasUnassigned = true;
            else if (seen.add(sid.toString())) sectionIds.add(sid.toString());
        }
        List<String> bucketSectionIds = new ArrayList<>(sectionIds);
        if (hasUnassigned) bucketSectionIds.add(null);

        // Hand off to the orchestrator and return immediately. Submitted to the
        // executor explicitly — a self-invoked @Async method bypasses Spring's
        // proxy and would run the whole solve on this request thread.
        quota.record(userId, LlmQuotaService.KIND_SOLVE);
        CompletableFuture.runAsync(
            () -> dispatch(examId, userId, bucketSectionIds, totalTaskBlocks, taskRows),
            solverExecutor
        );

        return new DispatchPlan(bucketSectionIds.size(), totalTaskBlocks);
    }

    /**
     * Record which model is about to answer this examination, how hard it was
     * asked to think, and when — QA7.
     *
     * <p>An examination has at most one run, so this replaces any previous one:
     * the resets just above threw away the answers a prior run would explain.
     * ExamLense sends no reasoning parameter, so the level stored is the pinned
     * model's own documented default.
     */
    private void recordRun(Examination exam, String solverModel) {
        evaluationRunRepository.deleteByExamId(exam.getId());
        EvaluationRun run = new EvaluationRun();
        run.setExamId(exam.getId());
        run.setSolverModel(solverModel);
        run.setThinkingLevel(SolverStrategies.resolve(solverModel).defaultThinking());
        evaluationRunRepository.save(run);
    }

    private void dispatch(
        String examId, String userId,
        List<String> sectionIds, int totalTaskBlocks,
        List<TaskBlock> allTaskBlockRows
    ) {
        try {
            runDispatch(examId, userId, sectionIds, totalTaskBlocks, allTaskBlockRows);
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
        List<String> sectionIds, int totalTaskBlocks,
        List<TaskBlock> allTaskBlockRows
    ) throws InterruptedException {
        UUID examUuid = Access.id(examId);
        Semaphore slots = new Semaphore(MAX_CONCURRENT);

        joinAll(dispatchAll(slots, examId, userId, sectionIds));

        // Verify completion: some sections may have returned empty or partial
        // answer arrays.
        long answered = taskAnswerRepository.countByExamId(examUuid);
        if (answered >= totalTaskBlocks) {
            // Compare-and-set: a since-cancelled exam sits in `ready`, so this
            // matches no row instead of dragging it into `grading`.
            examRepository.updateStatusIfCurrent(examUuid, "evaluating", "grading");
            sse.examUpdated(examUuid);
            return;
        }

        // One targeted sweep: redispatch sections that still have missing answers.
        Set<UUID> answeredIds = new HashSet<>();
        for (AIAnswer a : taskAnswerRepository.findByExamId(examUuid)) {
            answeredIds.add(a.getTaskId());
        }
        Set<String> missingSections = new LinkedHashSet<>();
        boolean missingUnassigned = false;
        for (TaskBlock t : allTaskBlockRows) {
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
        if (finalCount >= totalTaskBlocks) {
            // Same compare-and-set guard as above.
            examRepository.updateStatusIfCurrent(examUuid, "evaluating", "grading");
        } else {
            // markSolveFailed is itself guarded on status='evaluating', so a user
            // cancel (now `ready`, error cleared) isn't clobbered back to `failed`.
            examRepository.markSolveFailed(examUuid,
                "Evaluation incomplete: " + finalCount + "/" + totalTaskBlocks + " tasks answered");
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
