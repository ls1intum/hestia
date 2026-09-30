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
import de.tum.cit.hestia.learninggoalhub.extraction.TopicTreeSynthesizer.MenuTopic;
import de.tum.cit.hestia.learninggoalhub.extraction.TopicTreeSynthesizer.Placement;
import de.tum.cit.hestia.learninggoalhub.extraction.TopicTreeSynthesizer.PlannedTopic;
import de.tum.cit.hestia.learninggoalhub.goal.BloomLevel;
import de.tum.cit.hestia.learninggoalhub.goal.GoalOrigin;
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
 * Exercise outcomes and exam goals are evidence beneath the lecture tree: they never name, get
 * assigned or get structured with the lecture outcomes, and are placed onto the finished topics
 * afterwards.
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

    private static final List<MenuTopic> TOPICS = List.of(
            new MenuTopic("Sorting", List.of()), new MenuTopic("Hashing", List.of()));

    @Test
    void onlyLectureAndKindlessOutcomesNameTheTopics() {
        stubCourse(List.of(lecture, practisedSorting, kindless, notIntroduced));
        stubLecturePlan();
        when(synthesizer.place(any(), any(), isNull())).thenReturn(Map.of(0, new Placement(0, null)));

        runner().rebuildCompetencyTree(1L, null, false);

        verify(synthesizer).synthesize(eq(List.of("Applying sorting", "Applying hashing")), anyString(), isNull());
        verify(synthesizer).place(eq(TOPICS),
                eq(List.of("Implementing merge sort", "Designing a compiler")), isNull());
    }

    @Test
    void placedExerciseOutcomesHangDirectlyUnderTheirTopicAndUnplacedOnesAreCounted() {
        stubCourse(List.of(lecture, practisedSorting, kindless, notIntroduced));
        stubLecturePlan();
        when(synthesizer.place(any(), any(), isNull())).thenReturn(Map.of(0, new Placement(0, null)));

        CompetencyTreeResult result = runner().rebuildCompetencyTree(1L, null, false);

        assertThat(result.competencies()).isEqualTo(2);
        assertThat(result.unmatchedGoals()).isEqualTo(1);
        assertThat(edges()).containsExactlyInAnyOrder(
                "Applying sorting -> Sorting",
                "Implementing merge sort -> Sorting",
                "Applying hashing -> Hashing");
    }

    @Test
    void anExerciseOutcomeCanBePlacedUnderASkillOfItsTopic() {
        stubCourse(List.of(lecture, kindless, practisedSorting));
        when(synthesizer.synthesize(anyList(), anyString(), isNull())).thenReturn(new TopicTreeSynthesizer.Plan(
                List.of(new PlannedTopic("Sorting",
                        List.of(new TopicTreeSynthesizer.PlannedCapability("Apply sorting and hashing", List.of(0, 1))),
                        List.of())),
                List.of()));
        when(synthesizer.place(any(), any(), isNull())).thenReturn(Map.of(0, new Placement(0, 0)));
        // The generated skill is linked by id, which the database would assign on save.
        java.util.concurrent.atomic.AtomicLong ids = new java.util.concurrent.atomic.AtomicLong(100);
        when(goalRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            LearningGoal saved = invocation.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(saved, "id", ids.incrementAndGet());
            return saved;
        });

        runner().rebuildCompetencyTree(1L, null, false);

        verify(synthesizer).place(eq(List.of(new MenuTopic("Sorting", List.of("Apply sorting and hashing")))),
                eq(List.of("Implementing merge sort")), isNull());
        assertThat(edges()).containsExactlyInAnyOrder(
                "Apply sorting and hashing -> Sorting",
                "Applying sorting -> Apply sorting and hashing",
                "Applying hashing -> Apply sorting and hashing",
                "Implementing merge sort -> Apply sorting and hashing");
    }

    @Test
    void aCourseWithoutExercisesMakesNoPlacementCall() {
        stubCourse(List.of(lecture, kindless));
        stubLecturePlan();

        CompetencyTreeResult result = runner().rebuildCompetencyTree(1L, null, false);

        assertThat(result.unmatchedGoals()).isZero();
        verify(synthesizer, never()).place(anyList(), anyList(), any());
    }

    @Test
    void examGoalsNeverSeedTheTreeAndAreOfferedSkillsOnlyWhenSkillTier() {
        LearningGoal examined = examGoal(5L, "Apply merge sort to a given list", null, BloomLevel.APPLY);
        LearningGoal notTaught = examGoal(6L, "Design a lexer", GoalRole.SKILL, BloomLevel.CREATE);
        LearningGoal recalled = examGoal(7L, "Recall what a hash collision is", GoalRole.KNOWLEDGE, BloomLevel.REMEMBER);
        stubCourse(List.of(lecture, kindless, examined, notTaught, recalled));
        when(goalRepository.findByCourseIdAndOriginIn(1L, List.of(GoalOrigin.EXAM)))
                .thenReturn(List.of(examined, notTaught, recalled));
        stubLecturePlan();
        when(synthesizer.place(any(), eq(List.of("Apply merge sort to a given list", "Design a lexer")), isNull()))
                .thenReturn(Map.of(0, new Placement(0, null)));
        when(synthesizer.place(any(), eq(List.of("Recall what a hash collision is")), isNull()))
                .thenReturn(Map.of(0, new Placement(1, null)));

        CompetencyTreeResult result = runner().rebuildCompetencyTree(1L, null, false);

        verify(synthesizer).synthesize(eq(List.of("Applying sorting", "Applying hashing")), anyString(), isNull());
        verify(synthesizer).place(eq(TOPICS), eq(List.of("Recall what a hash collision is")), isNull());
        assertThat(result.unmatchedGoals()).isZero();
        assertThat(edges()).containsExactlyInAnyOrder(
                "Applying sorting -> Sorting",
                "Applying hashing -> Hashing",
                "Apply merge sort to a given list -> Sorting",
                "Recall what a hash collision is -> Hashing");
    }

    @Test
    void aFailedExamPlacementStillBuildsTheTree() {
        LearningGoal examined = examGoal(5L, "Apply merge sort to a given list", null, BloomLevel.APPLY);
        stubCourse(List.of(lecture, kindless, examined));
        when(goalRepository.findByCourseIdAndOriginIn(1L, List.of(GoalOrigin.EXAM))).thenReturn(List.of(examined));
        stubLecturePlan();
        when(synthesizer.place(any(), any(), isNull())).thenThrow(new IllegalStateException("model down"));

        CompetencyTreeResult result = runner().rebuildCompetencyTree(1L, null, false);

        assertThat(result.competencies()).isEqualTo(2);
    }

    private List<String> edges() {
        ArgumentCaptor<GoalRelationship> edges = ArgumentCaptor.forClass(GoalRelationship.class);
        verify(relationshipRepository, org.mockito.Mockito.atLeastOnce()).save(edges.capture());
        return edges.getAllValues().stream()
                .map(edge -> edge.getSource().getText() + " -> " + edge.getTarget().getText())
                .toList();
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

    /** An exam goal as the exam endpoint stores it: on the EXAM root, no document. */
    private static LearningGoal examGoal(long id, String text, GoalRole role, BloomLevel bloom) {
        HierarchyNode node = mock(HierarchyNode.class);
        when(node.getLevel()).thenReturn(HierarchyLevel.EXAM);
        LearningGoal goal = mock(LearningGoal.class);
        when(goal.getId()).thenReturn(id);
        when(goal.getText()).thenReturn(text);
        when(goal.getRole()).thenReturn(role);
        when(goal.getBloomLevel()).thenReturn(bloom);
        when(goal.getOrigin()).thenReturn(GoalOrigin.EXAM);
        when(goal.getHierarchyNode()).thenReturn(node);
        return goal;
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
