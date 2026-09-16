package de.tum.cit.hestia.learninggoalhub.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.hestia.learninggoalhub.course.Course;
import de.tum.cit.hestia.learninggoalhub.course.CourseRepository;
import de.tum.cit.hestia.learninggoalhub.document.Document;
import de.tum.cit.hestia.learninggoalhub.document.DocumentContentRepository;
import de.tum.cit.hestia.learninggoalhub.document.DocumentKind;
import de.tum.cit.hestia.learninggoalhub.document.DocumentRepository;
import de.tum.cit.hestia.learninggoalhub.document.DocumentSectionRepository;
import de.tum.cit.hestia.learninggoalhub.document.PageDescriptionRepository;
import de.tum.cit.hestia.learninggoalhub.document.PageDescriptionService;
import de.tum.cit.hestia.learninggoalhub.extraction.ExtractionRunner.CompetencyTreeResult;
import de.tum.cit.hestia.learninggoalhub.extraction.TopicTreeSynthesizer.PlannedTopic;
import de.tum.cit.hestia.learninggoalhub.goal.GoalRole;
import de.tum.cit.hestia.learninggoalhub.goal.GoalSourceRepository;
import de.tum.cit.hestia.learninggoalhub.goal.LearningGoal;
import de.tum.cit.hestia.learninggoalhub.goal.LearningGoalRepository;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyLevel;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNode;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNodeRepository;
import de.tum.cit.hestia.learninggoalhub.relationships.GoalRelationship;
import de.tum.cit.hestia.learninggoalhub.relationships.GoalRelationshipRepository;
import de.tum.cit.hestia.learninggoalhub.taxonomy.TaxonomyService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Exercise outcomes are evidence beneath the lecture tree: they never name, get assigned or get
 * structured with the lecture outcomes, and are placed onto the finished topics afterwards.
 */
class ExtractionRunnerExercisePlacementTest {

    private final CourseRepository courseRepository = mock(CourseRepository.class);
    private final LearningGoalRepository goalRepository = mock(LearningGoalRepository.class);
    private final GoalRelationshipRepository relationshipRepository = mock(GoalRelationshipRepository.class);
    private final TopicTreeSynthesizer synthesizer = mock(TopicTreeSynthesizer.class);

    private final LearningGoal lecture = goal(1L, "Applying sorting", DocumentKind.LECTURE, 0);
    private final LearningGoal kindless = goal(2L, "Applying hashing", null, 1);
    private final LearningGoal practisedSorting = goal(3L, "Implementing merge sort", DocumentKind.EXERCISE, 2);
    private final LearningGoal notIntroduced = goal(4L, "Designing a compiler", DocumentKind.EXERCISE, 3);

    @Test
    void onlyLectureAndKindlessOutcomesNameTheTopics() {
        stubCourse(List.of(lecture, practisedSorting, kindless, notIntroduced));
        stubLecturePlan();
        when(synthesizer.assign(any(), any(), isNull())).thenReturn(Map.of(0, 0, 1, TopicTreeSynthesizer.UNMATCHED));

        runner().rebuildCompetencyTree(1L, null, false);

        verify(synthesizer).synthesize(eq(List.of("Applying sorting", "Applying hashing")), anyString(), isNull());
        verify(synthesizer).assign(eq(List.of("Sorting", "Hashing")),
                eq(List.of("Implementing merge sort", "Designing a compiler")), isNull());
    }

    @Test
    void assignedExerciseOutcomesHangDirectlyUnderTheirTopicAndUnassignedOnesAreCounted() {
        stubCourse(List.of(lecture, practisedSorting, kindless, notIntroduced));
        stubLecturePlan();
        when(synthesizer.assign(any(), any(), isNull())).thenReturn(Map.of(0, 0, 1, TopicTreeSynthesizer.UNMATCHED));

        CompetencyTreeResult result = runner().rebuildCompetencyTree(1L, null, false);

        assertThat(result.competencies()).isEqualTo(2);
        assertThat(result.unmatchedGoals()).isEqualTo(1);
        ArgumentCaptor<GoalRelationship> edges = ArgumentCaptor.forClass(GoalRelationship.class);
        verify(relationshipRepository, org.mockito.Mockito.atLeastOnce()).save(edges.capture());
        assertThat(edges.getAllValues())
                .extracting(edge -> edge.getSource().getText() + " -> " + edge.getTarget().getText())
                .containsExactlyInAnyOrder(
                        "Applying sorting -> Sorting",
                        "Implementing merge sort -> Sorting",
                        "Applying hashing -> Hashing");
    }

    @Test
    void aCourseWithoutExercisesMakesNoAssignmentCall() {
        stubCourse(List.of(lecture, kindless));
        stubLecturePlan();

        CompetencyTreeResult result = runner().rebuildCompetencyTree(1L, null, false);

        assertThat(result.unmatchedGoals()).isZero();
        verify(synthesizer, never()).assign(anyList(), anyList(), any());
    }

    private void stubLecturePlan() {
        when(synthesizer.synthesize(anyList(), anyString(), isNull())).thenReturn(new TopicTreeSynthesizer.Plan(
                List.of(new PlannedTopic("Sorting", List.of(), List.of(0)),
                        new PlannedTopic("Hashing", List.of(), List.of(1))),
                List.of()));
    }

    private void stubCourse(List<LearningGoal> goals) {
        Course course = mock(Course.class);
        when(course.getId()).thenReturn(1L);
        when(courseRepository.findById(1L)).thenReturn(Optional.of(course));
        when(goalRepository.findByCourseIdAndHierarchyNodeIsNotNull(1L)).thenReturn(goals);
    }

    private ExtractionRunner runner() {
        return new ExtractionRunner(courseRepository, mock(DocumentRepository.class),
                mock(DocumentContentRepository.class), mock(PageDescriptionService.class),
                mock(PageDescriptionRepository.class), goalRepository, mock(GoalSourceRepository.class),
                relationshipRepository, mock(UnitExtractor.class), mock(ExtractionRunAuditService.class),
                mock(DocumentSectionRepository.class), synthesizer, mock(HierarchyNodeRepository.class),
                mock(TaxonomyService.class), new ExtractionProgressTracker(),
                TransactionOperations.withoutTransaction(), 1, 1, false, null);
    }

    private static LearningGoal goal(long id, String text, DocumentKind kind, int lectureOrder) {
        Document document = mock(Document.class);
        when(document.getKind()).thenReturn(kind);
        HierarchyNode node = mock(HierarchyNode.class);
        when(node.getLevel()).thenReturn(kind == DocumentKind.EXERCISE ? HierarchyLevel.EXERCISE : HierarchyLevel.SESSION);
        when(node.getDocument()).thenReturn(document);
        LearningGoal goal = mock(LearningGoal.class);
        when(goal.getId()).thenReturn(id);
        when(goal.getText()).thenReturn(text);
        when(goal.getRole()).thenReturn(GoalRole.SKILL);
        when(goal.getLectureOrder()).thenReturn(lectureOrder);
        when(goal.getHierarchyNode()).thenReturn(node);
        return goal;
    }
}
