package app.solve;

import app.shared.Access;
import app.exam.Exam;
import app.task.Task;
import app.exam.ExamRepository;
import app.section.SectionRepository;
import app.task.TaskAnswerRepository;
import app.grading.TaskGradeRepository;
import app.task.TaskRepository;
import app.sse.SseHub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contract test for the fire-and-forget dispatch: {@code startEvaluation}
 * must return while section solving is still running (a regression here means
 * the HTTP request thread blocks for the whole exam solve — the old @Async
 * self-invocation bug), and the orchestrator must still finalize the exam.
 */
class SolveExamServiceAsyncTest {

    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void startEvaluationReturnsWhileSolvingIsStillRunning() throws Exception {
        ExamRepository examRepository = mock(ExamRepository.class);
        SectionRepository sectionRepository = mock(SectionRepository.class);
        TaskRepository taskRepository = mock(TaskRepository.class);
        TaskAnswerRepository answerRepository = mock(TaskAnswerRepository.class);
        TaskGradeRepository gradeRepository = mock(TaskGradeRepository.class);
        SolveSectionService sectionService = mock(SolveSectionService.class);
        SseHub sse = mock(SseHub.class);
        Access access = new Access(examRepository, sectionRepository);

        UUID examId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Exam exam = new Exam();
        exam.setOwnerId(ownerId);
        when(examRepository.findById(examId)).thenReturn(Optional.of(exam));

        Task task = new Task();
        task.setExamId(examId);
        task.setSectionId(UUID.randomUUID());
        when(taskRepository.findByExamIdOrderByPositionAsc(examId)).thenReturn(List.of(task));

        CountDownLatch solveStarted = new CountDownLatch(1);
        CountDownLatch releaseSolve = new CountDownLatch(1);
        when(sectionService.solve(any(), any(), any())).thenAnswer(inv -> {
            solveStarted.countDown();
            releaseSolve.await(5, TimeUnit.SECONDS);
            return new SolveSectionService.Result("ok", 1, 1);
        });
        when(answerRepository.countByExamId(examId)).thenReturn(1L);
        when(answerRepository.findByExamId(examId)).thenReturn(List.of());

        SolveExamService service = new SolveExamService(
            examRepository, taskRepository, answerRepository, gradeRepository,
            sectionService, executor, access, sse);

        SolveExamService.DispatchPlan plan = service.startEvaluation(examId.toString(), ownerId.toString());

        // startEvaluation already returned; the solve must still be blocked.
        assertThat(plan.sections()).isEqualTo(1);
        assertThat(plan.tasks()).isEqualTo(1);
        assertThat(solveStarted.await(2, TimeUnit.SECONDS))
            .as("section solve should have been dispatched to the pool")
            .isTrue();
        assertThat(releaseSolve.getCount())
            .as("startEvaluation returned while the solve was still in flight")
            .isEqualTo(1);

        // Let the orchestrator finish and verify it finalized the exam via CAS.
        releaseSolve.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        verify(examRepository).updateStatusIfCurrent(examId, "evaluating", "grading");
        verify(answerRepository).deleteByExamId(examId);
        verify(gradeRepository).deleteByExamIdAndAutoGradedTrue(examId);
    }

    @Test
    void examWithNoTasksIsFinalizedImmediately() {
        ExamRepository examRepository = mock(ExamRepository.class);
        SectionRepository sectionRepository = mock(SectionRepository.class);
        TaskRepository taskRepository = mock(TaskRepository.class);
        TaskAnswerRepository answerRepository = mock(TaskAnswerRepository.class);
        TaskGradeRepository gradeRepository = mock(TaskGradeRepository.class);
        SolveSectionService sectionService = mock(SolveSectionService.class);
        SseHub sse = mock(SseHub.class);
        Access access = new Access(examRepository, sectionRepository);

        UUID examId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Exam exam = new Exam();
        exam.setOwnerId(ownerId);
        when(examRepository.findById(examId)).thenReturn(Optional.of(exam));
        when(taskRepository.findByExamIdOrderByPositionAsc(examId)).thenReturn(List.of());

        SolveExamService service = new SolveExamService(
            examRepository, taskRepository, answerRepository, gradeRepository,
            sectionService, executor, access, sse);

        SolveExamService.DispatchPlan plan = service.startEvaluation(examId.toString(), ownerId.toString());

        assertThat(plan.sections()).isZero();
        assertThat(plan.tasks()).isZero();
        verify(examRepository).updateStatus(examId, "grading");
    }
}
