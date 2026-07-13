package de.tum.cit.hestia.learninggoalhub.relationships;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import de.tum.cit.hestia.learninggoalhub.TestcontainersConfiguration;
import de.tum.cit.hestia.learninggoalhub.course.Course;
import de.tum.cit.hestia.learninggoalhub.course.CourseRepository;
import de.tum.cit.hestia.learninggoalhub.goal.GoalKind;
import de.tum.cit.hestia.learninggoalhub.goal.LearningGoal;
import de.tum.cit.hestia.learninggoalhub.goal.LearningGoalRepository;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyLevel;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNode;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNodeRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, PrerequisiteLinker.class})
class PrerequisiteLinkerTest {

    @Autowired
    private CourseRepository courseRepository;

    @Autowired
    private HierarchyNodeRepository hierarchyNodeRepository;

    @Autowired
    private LearningGoalRepository goalRepository;

    @Autowired
    private GoalRelationshipRepository relationshipRepository;

    @Autowired
    private PrerequisiteLinker linker;

    @MockitoBean
    private PrerequisiteService prerequisiteService;

    @Test
    void persistsOnlyVerdictsAtOrAboveConfidenceThreshold() {
        Course course = courseRepository.save(new Course("SE"));
        HierarchyNode session = hierarchyNodeRepository.save(
                new HierarchyNode(course, null, HierarchyLevel.SESSION, "S1"));

        LearningGoal a = saveGoal(course, "Understand TDD basics.", session, 0);
        LearningGoal b = saveGoal(course, "Apply red-green-refactor.", session, 1);
        LearningGoal c = saveGoal(course, "Design test suites.", session, 2);

        // Judge by pair content (candidate order is decided by the embedding query, not the test):
        // Understand→Apply is a strong prerequisite, Apply→Design is below threshold, the rest NONE.
        when(prerequisiteService.judge(anyList(), any())).thenAnswer(inv -> {
            List<GoalPair> pairs = inv.getArgument(0);
            List<PrerequisitePairJudgment> out = new ArrayList<>();
            for (int i = 0; i < pairs.size(); i++) {
                GoalPair p = pairs.get(i);
                Set<String> texts = Set.of(p.a(), p.b());
                if (texts.equals(Set.of("Understand TDD basics.", "Apply red-green-refactor."))) {
                    out.add(new PrerequisitePairJudgment(i + 1, directionFrom(p, "Understand TDD basics."), 0.85));
                } else if (texts.equals(Set.of("Apply red-green-refactor.", "Design test suites."))) {
                    out.add(new PrerequisitePairJudgment(i + 1, directionFrom(p, "Apply red-green-refactor."), 0.65));
                } else {
                    out.add(new PrerequisitePairJudgment(i + 1, PrerequisiteDirection.NONE, 0.0));
                }
            }
            return out;
        });

        int created = linker.linkCourse(course.getId(), null);

        assertThat(created).isOne();
        List<GoalRelationship> all = relationshipRepository.findAll();
        assertThat(all).hasSize(1);
        GoalRelationship r = all.get(0);
        assertThat(r.getSource().getId()).isEqualTo(a.getId());
        assertThat(r.getTarget().getId()).isEqualTo(b.getId());
        assertThat(r.getType()).isEqualTo(RelationshipType.PREREQUISITE_OF);
        assertThat(r.getOrigin()).isEqualTo(RelationshipOrigin.LLM);
        assertThat(r.getConfidence()).isEqualTo(0.85);
        assertThat(c.getId()).isNotNull();
    }

    @Test
    void doesNotPairGoalsAcrossLevelsOrSingletonLevels() {
        Course course = courseRepository.save(new Course("SE"));
        HierarchyNode module = hierarchyNodeRepository.save(
                new HierarchyNode(course, null, HierarchyLevel.MODULE, "M"));
        HierarchyNode session = hierarchyNodeRepository.save(
                new HierarchyNode(course, module, HierarchyLevel.SESSION, "S"));

        saveGoal(course, "module goal", module, 0);
        saveGoal(course, "session goal", session, 1);

        int created = linker.linkCourse(course.getId(), null);

        // Each level has a single goal, so no same-level neighbour exists and no candidate pair is
        // formed — the judge is never reached.
        assertThat(created).isZero();
        assertThat(relationshipRepository.count()).isZero();
    }

    @Test
    void isIdempotentOnSecondRun() {
        Course course = courseRepository.save(new Course("SE"));
        HierarchyNode session = hierarchyNodeRepository.save(
                new HierarchyNode(course, null, HierarchyLevel.SESSION, "S"));
        saveGoal(course, "A", session, 0);
        saveGoal(course, "B", session, 1);

        when(prerequisiteService.judge(anyList(), any())).thenAnswer(inv -> {
            List<GoalPair> pairs = inv.getArgument(0);
            List<PrerequisitePairJudgment> out = new ArrayList<>();
            for (int i = 0; i < pairs.size(); i++) {
                out.add(new PrerequisitePairJudgment(i + 1, PrerequisiteDirection.A_BEFORE_B, 0.9));
            }
            return out;
        });

        assertThat(linker.linkCourse(course.getId(), null)).isOne();
        assertThat(linker.linkCourse(course.getId(), null)).isZero();
        assertThat(relationshipRepository.count()).isOne();
    }

    /** A_BEFORE_B if {@code from} is the pair's A side, otherwise B_BEFORE_A. */
    private static PrerequisiteDirection directionFrom(GoalPair pair, String from) {
        return pair.a().equals(from) ? PrerequisiteDirection.A_BEFORE_B : PrerequisiteDirection.B_BEFORE_A;
    }

    private LearningGoal saveGoal(Course course, String text, HierarchyNode node, int embeddingSlot) {
        LearningGoal g = new LearningGoal(course, text, GoalKind.EXPLICIT);
        g.setHierarchyNode(node);
        g.setEmbedding(orthogonalEmbedding(embeddingSlot));
        return goalRepository.saveAndFlush(g);
    }

    private static float[] orthogonalEmbedding(int slot) {
        float[] v = new float[4096];
        v[slot] = 1.0f;
        return v;
    }
}
