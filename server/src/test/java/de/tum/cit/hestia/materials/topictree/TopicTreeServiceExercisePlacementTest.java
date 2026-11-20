package de.tum.cit.hestia.materials.topictree;

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

import de.tum.cit.hestia.core.DomainModule;
import de.tum.cit.hestia.core.GoalInfo;
import de.tum.cit.hestia.core.GoalStatus;
import de.tum.cit.hestia.core.LearningGoalApi;
import de.tum.cit.hestia.materials.course.CourseContext;
import de.tum.cit.hestia.materials.course.MaterialSettingsService;
import de.tum.cit.hestia.materials.goal.GoalPlacement;
import de.tum.cit.hestia.materials.goal.NewPlacedGoal;
import de.tum.cit.hestia.materials.goal.PlacedGoal;
import de.tum.cit.hestia.materials.goal.PlacedGoals;
import de.tum.cit.hestia.core.GoalKind;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Predicate;
import de.tum.cit.hestia.materials.document.Document;
import de.tum.cit.hestia.materials.document.DocumentKind;
import de.tum.cit.hestia.materials.document.DocumentRepository;
import de.tum.cit.hestia.materials.topictree.TopicTreeSynthesizer.MenuTopic;
import de.tum.cit.hestia.materials.topictree.TopicTreeSynthesizer.Placement;
import de.tum.cit.hestia.materials.topictree.TopicTreeSynthesizer.PlannedTopic;
import de.tum.cit.hestia.core.GoalOrigin;
import de.tum.cit.hestia.core.GoalRole;
import de.tum.cit.hestia.materials.hierarchy.HierarchyLevel;
import de.tum.cit.hestia.materials.hierarchy.HierarchyNode;
import de.tum.cit.hestia.materials.hierarchy.HierarchyNodeRepository;
import de.tum.cit.hestia.materials.taxonomy.BloomLevel;
import de.tum.cit.hestia.materials.taxonomy.TaxonomyService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Exercise outcomes and exam goals are evidence beneath the lecture tree: they never name, get
 * assigned or get structured with the lecture outcomes, and are placed onto the finished topics
 * afterwards.
 */
class TopicTreeServiceExercisePlacementTest {

    private static final UUID COURSE = UUID.randomUUID();

    private final MaterialSettingsService settingsService = mock(MaterialSettingsService.class);
    private final PlacedGoals goals = mock(PlacedGoals.class);
    private final LearningGoalApi goalApi = mock(LearningGoalApi.class);
    private final TopicTreeSynthesizer synthesizer = mock(TopicTreeSynthesizer.class);
    /** The text of every goal by id, the stored ones and the ones the service creates. */
    private final Map<UUID, String> texts = new java.util.HashMap<>();

    private final PlacedGoal lecture = goal(1L, "Applying sorting", DocumentKind.LECTURE, 0);
    private final PlacedGoal kindless = goal(2L, "Applying hashing", null, 1);
    private final PlacedGoal practisedSorting = goal(3L, "Implementing merge sort", DocumentKind.EXERCISE, 2);
    private final PlacedGoal notIntroduced = goal(4L, "Designing a compiler", DocumentKind.EXERCISE, 3);

    private static final List<MenuTopic> TOPICS = List.of(
            new MenuTopic("Sorting", List.of()), new MenuTopic("Hashing", List.of()));

    @Test
    void onlyLectureAndKindlessOutcomesNameTheTopics() {
        stubCourse(List.of(lecture, practisedSorting, kindless, notIntroduced));
        stubLecturePlan();
        when(synthesizer.place(any(), any(), isNull())).thenReturn(Map.of(0, new Placement(0, null)));

        trees().rebuildTopicTree(COURSE, null, false);

        verify(synthesizer).synthesize(eq(List.of("Applying sorting", "Applying hashing")), anyString(), isNull());
        verify(synthesizer).place(eq(TOPICS),
                eq(List.of("Implementing merge sort", "Designing a compiler")), isNull());
    }

    @Test
    void placedExerciseOutcomesHangDirectlyUnderTheirTopicAndUnplacedOnesAreCounted() {
        stubCourse(List.of(lecture, practisedSorting, kindless, notIntroduced));
        stubLecturePlan();
        when(synthesizer.place(any(), any(), isNull())).thenReturn(Map.of(0, new Placement(0, null)));

        CompetencyTreeResult result = trees().rebuildTopicTree(COURSE, null, false);

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

        trees().rebuildTopicTree(COURSE, null, false);

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

        CompetencyTreeResult result = trees().rebuildTopicTree(COURSE, null, false);

        assertThat(result.unmatchedGoals()).isZero();
        verify(synthesizer, never()).place(anyList(), anyList(), any());
    }

    @Test
    void examGoalsNeverSeedTheTreeAndAreOfferedSkillsOnlyWhenSkillTier() {
        PlacedGoal examined = examGoal(5L, "Apply merge sort to a given list", null, BloomLevel.APPLY);
        PlacedGoal notTaught = examGoal(6L, "Design a lexer", GoalRole.SKILL, BloomLevel.CREATE);
        PlacedGoal recalled = examGoal(7L, "Recall what a hash collision is", GoalRole.KNOWLEDGE, BloomLevel.REMEMBER);
        stubCourse(List.of(lecture, kindless, examined, notTaught, recalled));
        stubLecturePlan();
        when(synthesizer.place(any(), eq(List.of("Apply merge sort to a given list", "Design a lexer")), isNull()))
                .thenReturn(Map.of(0, new Placement(0, null)));
        when(synthesizer.place(any(), eq(List.of("Recall what a hash collision is")), isNull()))
                .thenReturn(Map.of(0, new Placement(1, null)));

        CompetencyTreeResult result = trees().rebuildTopicTree(COURSE, null, false);

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
        PlacedGoal examined = examGoal(5L, "Apply merge sort to a given list", null, BloomLevel.APPLY);
        stubCourse(List.of(lecture, kindless, examined));
        stubLecturePlan();
        when(synthesizer.place(any(), any(), isNull())).thenThrow(new IllegalStateException("model down"));

        CompetencyTreeResult result = trees().rebuildTopicTree(COURSE, null, false);

        assertThat(result.competencies()).isEqualTo(2);
    }

    private List<String> edges() {
        ArgumentCaptor<UUID> sources = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<UUID> targets = ArgumentCaptor.forClass(UUID.class);
        verify(goalApi, org.mockito.Mockito.atLeastOnce())
                .relate(sources.capture(), targets.capture(), anyString(), org.mockito.ArgumentMatchers.anyDouble(),
                        anyString());
        List<String> edges = new java.util.ArrayList<>();
        for (int i = 0; i < sources.getAllValues().size(); i++) {
            edges.add(texts.get(sources.getAllValues().get(i)) + " -> " + texts.get(targets.getAllValues().get(i)));
        }
        return edges;
    }

    private void stubLecturePlan() {
        when(synthesizer.synthesize(anyList(), anyString(), isNull())).thenReturn(new TopicTreeSynthesizer.Plan(
                List.of(new PlannedTopic("Sorting", List.of(), List.of(0)),
                        new PlannedTopic("Hashing", List.of(), List.of(1))),
                List.of()));
    }

    private void stubCourse(List<PlacedGoal> placed) {
        when(settingsService.context(COURSE)).thenReturn(new CourseContext(COURSE, "Course", null, false));
        when(goals.list(COURSE)).thenReturn(placed);
        when(goals.list(eq(COURSE), any())).thenAnswer(invocation -> {
            Predicate<PlacedGoal> keep = invocation.getArgument(1);
            return placed.stream().filter(keep).toList();
        });
        // The goals the service creates get their ids here, as core would assign them.
        when(goals.createAll(eq(COURSE), anyList())).thenAnswer(invocation -> {
            List<NewPlacedGoal> items = invocation.getArgument(1);
            return items.stream().map(item -> {
                PlacedGoal created = new PlacedGoal(info(UUID.randomUUID(), item.goal().text(), item.goal().role(),
                        null, item.goal().origin()), null);
                texts.put(created.id(), created.text());
                return created;
            }).toList();
        });
    }

    private TopicTreeService trees() {
        return new TopicTreeService(settingsService, mock(DocumentRepository.class), goals, goalApi,
                mock(HierarchyNodeRepository.class), synthesizer, mock(TaxonomyService.class),
                TransactionOperations.withoutTransaction());
    }

    private static GoalInfo info(UUID id, String text, GoalRole role, BloomLevel bloom, GoalOrigin origin) {
        return new GoalInfo(id, COURSE, 1, text, text, null, GoalKind.IMPLICIT, role,
                bloom == null ? null : bloom.name(), null, GoalStatus.PENDING, origin, null, DomainModule.MATERIALS,
                null, Instant.now(), null);
    }

    private PlacedGoal placed(String text, GoalRole role, BloomLevel bloom, GoalOrigin origin, HierarchyNode node,
                              Integer lectureOrder) {
        UUID id = UUID.randomUUID();
        texts.put(id, text);
        return new PlacedGoal(info(id, text, role, bloom, origin), new GoalPlacement(id, node, null, lectureOrder));
    }

    /** An exam goal as the exam endpoint stores it: on the EXAM root, no document. */
    private PlacedGoal examGoal(long id, String text, GoalRole role, BloomLevel bloom) {
        HierarchyNode node = mock(HierarchyNode.class);
        when(node.getLevel()).thenReturn(HierarchyLevel.EXAM);
        return placed(text, role, bloom, GoalOrigin.EXAM, node, null);
    }

    private PlacedGoal goal(long id, String text, DocumentKind kind, int lectureOrder) {
        Document document = mock(Document.class);
        when(document.getKind()).thenReturn(kind);
        HierarchyNode node = mock(HierarchyNode.class);
        when(node.getLevel()).thenReturn(kind == DocumentKind.EXERCISE ? HierarchyLevel.EXERCISE : HierarchyLevel.SESSION);
        when(node.getDocument()).thenReturn(document);
        return placed(text, GoalRole.SKILL, null, GoalOrigin.EXTRACTED, node, lectureOrder);
    }
}
