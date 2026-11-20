package de.tum.cit.hestia.assessment.lgh;

import de.tum.cit.hestia.assessment.examination.Examination;
import de.tum.cit.hestia.assessment.examination.ExaminationRepository;
import de.tum.cit.hestia.assessment.section.Section;
import de.tum.cit.hestia.assessment.section.SectionBlockRepository;
import de.tum.cit.hestia.assessment.section.SectionRepository;
import de.tum.cit.hestia.assessment.sse.SseHub;
import de.tum.cit.hestia.assessment.taskblock.TaskBlock;
import de.tum.cit.hestia.assessment.taskblock.TaskBlockRepository;
import de.tum.cit.hestia.core.AlignmentApi;
import de.tum.cit.hestia.core.AlignmentLinkInfo;
import de.tum.cit.hestia.core.DomainModule;
import de.tum.cit.hestia.core.GoalInfo;
import de.tum.cit.hestia.core.GoalKind;
import de.tum.cit.hestia.core.GoalOrigin;
import de.tum.cit.hestia.core.GoalStatus;
import de.tum.cit.hestia.core.LearningGoalApi;
import de.tum.cit.hestia.core.spi.ExamGoalDerivation;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Learning-goal generation is deliberately decoupled from the exam flow: a failing derivation or a
 * vanished exam must never fail (or block) parsing/solving. These tests pin that degradation
 * contract, and that derived goals are linked to their tasks and cleaned up on re-confirm. The
 * derivation itself (materials) is mocked.
 */
class TaskBlockGoalGenerationServiceTest {

    private final ExaminationRepository exams = mock(ExaminationRepository.class);
    private final SectionRepository sections = mock(SectionRepository.class);
    private final SectionBlockRepository blocks = mock(SectionBlockRepository.class);
    private final TaskBlockRepository tasks = mock(TaskBlockRepository.class);
    private final ExamGoalDerivation derivation = mock(ExamGoalDerivation.class);
    private final TaskGoalLinks goalLinks = mock(TaskGoalLinks.class);
    private final LearningGoalApi goalApi = mock(LearningGoalApi.class);
    private final AlignmentApi alignment = mock(AlignmentApi.class);
    private final SseHub sse = mock(SseHub.class);

    private final TaskBlockGoalGenerationService service = new TaskBlockGoalGenerationService(
        exams, sections, blocks, tasks, derivation, goalLinks, goalApi, alignment, sse);

    private final UUID courseId = UUID.randomUUID();
    private final UUID examId = UUID.randomUUID();
    private final Section section = new Section();
    private final TaskBlock task = new TaskBlock();

    private static GoalInfo goal(UUID id, UUID course, GoalOrigin origin, DomainModule createdVia) {
        return new GoalInfo(id, course, 1, "text", "text", null, GoalKind.EXPLICIT, null, null, null,
            GoalStatus.PENDING, origin, null, createdVia, null, Instant.now(), null);
    }

    /** An exam of {@link #courseId} with one confirmed section holding one task. */
    private void confirmedSection() {
        Examination exam = new Examination();
        exam.setCourseId(courseId);
        when(exams.findById(examId)).thenReturn(Optional.of(exam));
        section.setExamId(examId);
        section.setConfirmedAt(OffsetDateTime.now());
        when(sections.findById(section.getId())).thenReturn(Optional.of(section));
        when(sections.acquireGoalsLock(eq(section.getId()), eq(examId), any(), any())).thenReturn(1);
        task.setExamId(examId);
        task.setSectionId(section.getId());
        task.setType("text");
        task.setPrompt("Explain recursion");
        when(tasks.findByExamIdAndSectionIdOrderByPositionAsc(examId, section.getId())).thenReturn(List.of(task));
        when(goalLinks.linkedGoalIds(any())).thenReturn(List.of());
    }

    @Test
    void cleanupSwallowsFailuresAndStillAttemptsEveryGoal() {
        doThrow(new RuntimeException("not allowed")).when(goalApi).remove(any());

        // Must not propagate - unconfirm cleanup is best-effort.
        service.dispatchCleanup(List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));

        verify(goalApi, times(3)).remove(any());
    }

    @Test
    void generationIsSkippedWhenTheExaminationNoLongerExists() {
        when(exams.findById(examId)).thenReturn(Optional.empty());

        service.dispatchGenerate(examId, UUID.randomUUID());

        verify(derivation, never()).derive(any(), anyList());
    }

    @Test
    void derivedGoalsAreLinkedToTheirTasksInTheExamsCourse() {
        confirmedSection();
        UUID goalId = UUID.randomUUID();
        when(derivation.derive(eq(courseId), anyList())).thenReturn(List.of(
            new ExamGoalDerivation.TaskGoals(task.getId().toString(),
                List.of(goal(goalId, courseId, GoalOrigin.EXAM, DomainModule.ASSESSMENT)))));

        service.dispatchGenerate(examId, section.getId());

        verify(goalLinks).link(goalId, task.getId());
        verify(sse).tasksUpdated(examId);
        verify(sections).releaseGoalsLock(section.getId());
    }

    @Test
    void reconfirmingRemovesTheGoalsDerivedBeforeAndKeepsOthers() {
        confirmedSection();
        UUID derived = UUID.randomUUID();
        UUID written = UUID.randomUUID();
        UUID shared = UUID.randomUUID();
        when(goalLinks.linkedGoalIds(task.getId())).thenReturn(List.of(derived, written, shared));
        when(goalApi.goalsById(anyCollection())).thenReturn(List.of(
            goal(derived, courseId, GoalOrigin.EXAM, DomainModule.ASSESSMENT),
            goal(written, courseId, GoalOrigin.MANUAL, DomainModule.TEACHING),
            goal(shared, courseId, GoalOrigin.EXAM, DomainModule.ASSESSMENT)));
        when(alignment.linksOfGoal(shared)).thenReturn(List.of(mock(AlignmentLinkInfo.class)));
        when(alignment.linksOfGoal(derived)).thenReturn(List.of());
        when(derivation.derive(eq(courseId), anyList())).thenReturn(List.of());

        service.dispatchGenerate(examId, section.getId());

        verify(goalLinks).unlinkAll(List.of(task.getId()));
        verify(goalApi).remove(derived);
        verify(goalApi, never()).remove(written);
        verify(goalApi, never()).remove(shared);
    }

    @Test
    void aResultOfASectionUnconfirmedMidRunIsDroppedAndItsGoalsRemoved() {
        confirmedSection();
        UUID goalId = UUID.randomUUID();
        when(derivation.derive(eq(courseId), anyList())).thenAnswer(inv -> {
            section.setConfirmedAt(null); // the user unconfirmed while the model ran
            return List.of(new ExamGoalDerivation.TaskGoals(task.getId().toString(),
                List.of(goal(goalId, courseId, GoalOrigin.EXAM, DomainModule.ASSESSMENT))));
        });

        service.dispatchGenerate(examId, section.getId());

        verify(goalApi).remove(goalId);
        verify(goalLinks, never()).link(any(), any());
    }

    @Test
    void aFailingDerivationDoesNotPropagateAndReleasesTheLock() {
        confirmedSection();
        when(derivation.derive(any(), anyList())).thenThrow(new IllegalStateException("model down"));

        service.dispatchGenerate(examId, section.getId());

        verify(goalLinks, never()).link(any(), any());
        verify(sections).releaseGoalsLock(section.getId());
    }
}
