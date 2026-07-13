package app.solve;

import app.api.Access;
import app.error.ApiException;
import app.persistence.entity.Exam;
import app.persistence.entity.Task;
import app.persistence.repository.ExamRepository;
import app.persistence.repository.TaskAnswerRepository;
import app.persistence.repository.TaskGradeRepository;
import app.persistence.repository.TaskRepository;
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
 * SolveExamService orchestrates the (async) evaluation. Ownership is delegated to
 * {@link Access}; this test pins that a rejected caller mutates no state, an
 * unknown exam is 404, and an exam with no tasks short-circuits straight to
 * grading without dispatching any solver work.
 */
class SolveExamServiceTest {

    private final ExamRepository exams = mock(ExamRepository.class);
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final TaskAnswerRepository answers = mock(TaskAnswerRepository.class);
    private final TaskGradeRepository grades = mock(TaskGradeRepository.class);
    private final SolveSectionService sectionService = mock(SolveSectionService.class);
    private final Executor executor = mock(Executor.class);
    private final Access access = mock(Access.class);
    private final SseHub sse = mock(SseHub.class);

    private final SolveExamService service =
        new SolveExamService(exams, tasks, answers, grades, sectionService, executor, access, sse);

    private static Exam examOwnedBy(UUID owner) {
        Exam e = new Exam();
        e.setOwnerId(owner);
        return e;
    }

    @Test
    void startEvaluationPropagatesForbiddenFromAccessAndMutatesNothing() {
        UUID examId = UUID.randomUUID();
        when(access.requireExam(eq(examId), any()))
            .thenThrow(new ApiException(HttpStatus.FORBIDDEN, "Forbidden"));

        assertThatThrownBy(() -> service.startEvaluation(examId.toString(), UUID.randomUUID().toString()))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.FORBIDDEN));

        verify(answers, never()).deleteByExamId(any()); // no state mutated on a rejected caller
    }

    @Test
    void startEvaluationPropagatesNotFoundFromAccess() {
        UUID examId = UUID.randomUUID();
        when(access.requireExam(eq(examId), any()))
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
    void emptyExamShortCircuitsStraightToGrading() {
        UUID owner = UUID.randomUUID();
        UUID examId = UUID.randomUUID();
        when(access.requireExam(eq(examId), eq(owner.toString()))).thenReturn(examOwnedBy(owner));
        when(tasks.findByExamIdOrderByPositionAsc(examId)).thenReturn(List.<Task>of());

        SolveExamService.DispatchPlan plan = service.startEvaluation(examId.toString(), owner.toString());

        assertThat(plan.sections()).isZero();
        assertThat(plan.tasks()).isZero();
        verify(exams).updateStatus(eq(examId), eq("grading"));
        verify(executor, never()).execute(any()); // nothing dispatched to the solver pool
    }
}
