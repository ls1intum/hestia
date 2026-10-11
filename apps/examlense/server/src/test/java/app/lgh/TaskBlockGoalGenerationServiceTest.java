package app.lgh;

import app.examination.Examination;
import app.examination.ExaminationRepository;
import app.section.SectionBlockRepository;
import app.section.SectionRepository;
import app.taskblock.TaskBlockRepository;
import app.sse.SseHub;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Learning-goal generation is deliberately decoupled from the exam flow: an LGH
 * outage or a missing course link must never fail (or block) parsing/solving.
 * These tests pin that degradation contract — the service quietly skips or
 * swallows instead of throwing.
 */
class TaskBlockGoalGenerationServiceTest {

    private final ExaminationRepository exams = mock(ExaminationRepository.class);
    private final SectionRepository sections = mock(SectionRepository.class);
    private final SectionBlockRepository blocks = mock(SectionBlockRepository.class);
    private final TaskBlockRepository tasks = mock(TaskBlockRepository.class);
    private final LearningGoalHubClient client = mock(LearningGoalHubClient.class);
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    private final SseHub sse = mock(SseHub.class);

    private final TaskBlockGoalGenerationService service =
        new TaskBlockGoalGenerationService(exams, sections, blocks, tasks, client, txManager, sse);

    @Test
    void cleanupSwallowsClientFailuresAndStillAttemptsEveryGoal() {
        doThrow(new RuntimeException("LGH down")).when(client).deleteGoal(anyLong(), anyLong());

        // Must not propagate — unconfirm cleanup is best-effort.
        service.dispatchCleanup(42L, List.of(1L, 2L, 3L));

        verify(client, times(3)).deleteGoal(anyLong(), anyLong());
    }

    @Test
    void generationIsSkippedWhenNoLghCourseIsLinked() {
        UUID examId = UUID.randomUUID();
        UUID sectionId = UUID.randomUUID();
        Examination exam = new Examination();
        exam.setLghCourseId(null); // course was optional at exam creation
        when(exams.findById(examId)).thenReturn(Optional.of(exam));

        service.dispatchGenerate(examId, sectionId);

        verify(client, never()).generateExamGoals(anyLong(), anyList());
    }

    @Test
    void generationIsSkippedWhenExaminationNoLongerExists() {
        UUID examId = UUID.randomUUID();
        when(exams.findById(examId)).thenReturn(Optional.empty());

        service.dispatchGenerate(examId, UUID.randomUUID());

        verify(client, never()).generateExamGoals(anyLong(), anyList());
    }
}
