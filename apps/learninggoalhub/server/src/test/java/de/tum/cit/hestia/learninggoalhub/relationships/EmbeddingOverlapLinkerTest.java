package de.tum.cit.hestia.learninggoalhub.relationships;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.hestia.learninggoalhub.TestcontainersConfiguration;
import de.tum.cit.hestia.learninggoalhub.course.Course;
import de.tum.cit.hestia.learninggoalhub.course.CourseRepository;
import de.tum.cit.hestia.learninggoalhub.goal.GoalKind;
import de.tum.cit.hestia.learninggoalhub.goal.LearningGoal;
import de.tum.cit.hestia.learninggoalhub.goal.LearningGoalRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, EmbeddingOverlapLinker.class})
class EmbeddingOverlapLinkerTest {

    private static final int DIM = 4096;

    @Autowired
    private CourseRepository courseRepository;

    @Autowired
    private LearningGoalRepository goalRepository;

    @Autowired
    private GoalRelationshipRepository relationshipRepository;

    @Autowired
    private EmbeddingOverlapLinker linker;

    @Test
    void linksPairsInsideOverlapBandOnly() {
        Course course = courseRepository.save(new Course("SE"));

        // A on axis 0; B at cosine 0.88 to A (inside band 0.85–0.92); C at cosine 0.95 to A (above
        // dedup, would have been merged); D at cosine 0.5 to A (below band).
        LearningGoal a = save(course, "A", unit2d(0, 1.0f, 0.0f));
        LearningGoal b = save(course, "B", unit2d(0, 0.88f, 0.4749737f)); // cos = 0.88
        LearningGoal c = save(course, "C", unit2d(0, 0.95f, 0.3122499f)); // cos(A,C) = 0.95
        LearningGoal d = save(course, "D", unitAxis(0, 0.5f, 3, 0.8660254f)); // cos(A,D) = 0.5

        int created = linker.linkCourse(course.getId());

        assertThat(created).isOne();

        List<GoalRelationship> all = relationshipRepository.findAll();
        assertThat(all).hasSize(1);
        GoalRelationship overlap = all.get(0);
        assertThat(overlap.getType()).isEqualTo(RelationshipType.OVERLAPS_WITH);
        assertThat(overlap.getOrigin()).isEqualTo(RelationshipOrigin.EMBEDDING);
        assertThat(overlap.getSource().getId()).isEqualTo(a.getId());
        assertThat(overlap.getTarget().getId()).isEqualTo(b.getId());
        assertThat(overlap.getConfidence()).isCloseTo(0.88, org.assertj.core.data.Offset.offset(0.001));

        // The other goals exist but are not referenced.
        assertThat(c.getId()).isNotNull();
        assertThat(d.getId()).isNotNull();
    }

    @Test
    void capsEachGoalsOverlapsAtMaxPerGoal() {
        Course course = courseRepository.save(new Course("SE"));

        // A is in-band-similar to four neighbours (0.91/0.90/0.89/0.88), each on its own axis so the
        // neighbours are mutually below the floor (e.g. 0.91·0.90 = 0.819 < 0.85). With max-per-goal=3
        // A keeps its three strongest overlaps; the weakest (0.88) is dropped.
        LearningGoal a = save(course, "A", unit2d(0, 1.0f, 0.0f));
        LearningGoal b91 = save(course, "B91", unitAxis(0, 0.91f, 1, 0.4146082f));
        LearningGoal b90 = save(course, "B90", unitAxis(0, 0.90f, 2, 0.4358899f));
        LearningGoal b89 = save(course, "B89", unitAxis(0, 0.89f, 3, 0.4559606f));
        LearningGoal b88 = save(course, "B88", unitAxis(0, 0.88f, 4, 0.4749737f));

        int created = linker.linkCourse(course.getId());

        assertThat(created).isEqualTo(3);
        List<GoalRelationship> all = relationshipRepository.findAll();
        assertThat(all).hasSize(3);
        assertThat(all).allMatch(r -> r.getSource().getId().equals(a.getId()));
        // The three strongest neighbours are linked; the weakest (0.88) is capped out.
        assertThat(all).extracting(r -> r.getTarget().getId())
                .containsExactlyInAnyOrder(b91.getId(), b90.getId(), b89.getId())
                .doesNotContain(b88.getId());
    }

    @Test
    void isIdempotentOnSecondRun() {
        Course course = courseRepository.save(new Course("SE"));
        save(course, "A", unit2d(0, 1.0f, 0.0f));
        save(course, "B", unit2d(0, 0.88f, 0.4749737f));

        int first = linker.linkCourse(course.getId());
        int second = linker.linkCourse(course.getId());

        assertThat(first).isOne();
        assertThat(second).isZero();
        assertThat(relationshipRepository.count()).isOne();
    }

    @Test
    void skipsGoalsWithoutEmbedding() {
        Course course = courseRepository.save(new Course("SE"));
        save(course, "A", unit2d(0, 1.0f, 0.0f));
        save(course, "B", null);

        int created = linker.linkCourse(course.getId());

        assertThat(created).isZero();
    }

    private LearningGoal save(Course course, String text, float[] embedding) {
        LearningGoal g = new LearningGoal(course, text, GoalKind.EXPLICIT);
        if (embedding != null) {
            g.setEmbedding(embedding);
        }
        return goalRepository.saveAndFlush(g);
    }

    /** Sparse 4096-d vector with non-zero entries at two indices. */
    private static float[] unit2d(int i, float vi, float vj) {
        float[] v = new float[DIM];
        v[i] = vi;
        v[i + 1] = vj;
        return v;
    }

    private static float[] unitAxis(int i, float vi, int j, float vj) {
        float[] v = new float[DIM];
        v[i] = vi;
        v[j] = vj;
        return v;
    }
}
