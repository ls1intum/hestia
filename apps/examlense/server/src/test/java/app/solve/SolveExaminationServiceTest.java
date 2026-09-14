package app.solve;

import app.user.LlmQuotaService;
import app.shared.Access;
import app.error.ApiException;
import app.examination.EvaluationRunRepository;
import app.examination.Examination;
import app.taskblock.TaskBlock;
import app.examination.ExaminationRepository;
import app.taskblock.AIAnswerRepository;
import app.grading.GradeRepository;
import app.taskblock.TaskBlockRepository;
import app.sse.SseHub;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SolveExaminationService orchestrates the (async) evaluation. Ownership is delegated to
 * {@link Access}; this test pins that a rejected caller mutates no state, an
 * unknown exam is 404, and an exam with no tasks short-circuits straight to
 * grading without dispatching any solver work.
 */
class SolveExaminationServiceTest {

    private final ExaminationRepository exams = mock(ExaminationRepository.class);
    private final TaskBlockRepository tasks = mock(TaskBlockRepository.class);
    private final AIAnswerRepository answers = mock(AIAnswerRepository.class);
    private final GradeRepository grades = mock(GradeRepository.class);
    private final SolveSectionService sectionService = mock(SolveSectionService.class);
    private final Executor executor = mock(Executor.class);
    private final Access access = mock(Access.class);
    private final SseHub sse = mock(SseHub.class);

    private final SolveExaminationService service =
        new SolveExaminationService(exams, tasks, answers, grades,
            mock(EvaluationRunRepository.class), sectionService, executor, access, sse,
            mock(LlmQuotaService.class));

    private static Examination examOwnedBy(UUID owner) {
        Examination e = new Examination();
        e.setOwnerId(owner);
        return e;
    }

    @Test
    void startEvaluationPropagatesForbiddenFromAccessAndMutatesNothing() {
        UUID examId = UUID.randomUUID();
        when(access.requireExamination(eq(examId), any()))
            .thenThrow(new ApiException(HttpStatus.FORBIDDEN, "Forbidden"));

        assertThatThrownBy(() -> service.startEvaluation(examId.toString(), UUID.randomUUID().toString()))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.FORBIDDEN));

        verify(answers, never()).deleteByExamId(any()); // no state mutated on a rejected caller
    }

    @Test
    void startEvaluationPropagatesNotFoundFromAccess() {
        UUID examId = UUID.randomUUID();
        when(access.requireExamination(eq(examId), any()))
            .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "Exam not found"));

        assertThatThrownBy(() -> service.startEvaluation(examId.toString(), UUID.randomUUID().toString()))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void malformedExamIdMapsTo404BeforeTouchingAccess() {
        assertThatThrownBy(() -> service.startEvaluation("not-a-uuid", UUID.randomUUID().toString()))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void emptyExaminationShortCircuitsStraightToGrading() {
        UUID owner = UUID.randomUUID();
        UUID examId = UUID.randomUUID();
        when(access.requireExamination(eq(examId), eq(owner.toString()))).thenReturn(examOwnedBy(owner));
        when(tasks.findByExamIdOrderByPositionAsc(examId)).thenReturn(List.<TaskBlock>of());
        // startEvaluating is a compare-and-set: 1 row means we won it. Must be stubbed —
        // an unstubbed int mock returns 0, which the service reads as "cancelled, bail".
        when(exams.startEvaluating(eq(examId), any())).thenReturn(1);

        SolveExaminationService.DispatchPlan plan = service.startEvaluation(examId.toString(), owner.toString());

        assertThat(plan.sections()).isZero();
        assertThat(plan.tasks()).isZero();
        verify(exams).updateStatus(eq(examId), eq("grading"));
        verify(executor, never()).execute(any()); // nothing dispatched to the solver pool
    }
}
