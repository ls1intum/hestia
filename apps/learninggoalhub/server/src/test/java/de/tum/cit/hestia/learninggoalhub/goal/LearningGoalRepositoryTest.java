package de.tum.cit.hestia.learninggoalhub.goal;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.hestia.learninggoalhub.TestcontainersConfiguration;
import de.tum.cit.hestia.learninggoalhub.course.Course;
import de.tum.cit.hestia.learninggoalhub.course.CourseRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class LearningGoalRepositoryTest {

    @Autowired
    private CourseRepository courseRepository;

    @Autowired
    private LearningGoalRepository goalRepository;

    @Test
    void persistsExplicitAndImplicitGoalsScopedToCourse() {
        Course se = courseRepository.save(new Course("Software Engineering"));
        Course db = courseRepository.save(new Course("Databases"));

        goalRepository.save(new LearningGoal(se, "Students can apply TDD.", GoalKind.EXPLICIT));
        goalRepository.save(new LearningGoal(se, "Students recognise the value of refactoring.", GoalKind.IMPLICIT));
        goalRepository.save(new LearningGoal(db, "Students can write joins.", GoalKind.EXPLICIT));

        List<LearningGoal> seGoals = goalRepository.findByCourseId(se.getId());

        assertThat(seGoals).hasSize(2);
        assertThat(seGoals).extracting(LearningGoal::getKind)
                .containsExactlyInAnyOrder(GoalKind.EXPLICIT, GoalKind.IMPLICIT);
        assertThat(seGoals).allSatisfy(g -> {
            assertThat(g.getCourse().getId()).isEqualTo(se.getId());
            assertThat(g.getCreatedAt()).isNotNull();
            assertThat(g.getBloomLevel()).isNull();
            assertThat(g.getSoloLevel()).isNull();
        });
    }

    @Test
    void persistsBloomAndSoloLevelsWhenSet() {
        Course se = courseRepository.save(new Course("Software Engineering"));

        LearningGoal goal = new LearningGoal(se, "Students can apply TDD.", GoalKind.EXPLICIT);
        goal.setBloomLevel(BloomLevel.APPLY);
        goal.setSoloLevel(SoloLevel.RELATIONAL);
        Long id = goalRepository.save(goal).getId();

        LearningGoal reloaded = goalRepository.findById(id).orElseThrow();
        assertThat(reloaded.getBloomLevel()).isEqualTo(BloomLevel.APPLY);
        assertThat(reloaded.getSoloLevel()).isEqualTo(SoloLevel.RELATIONAL);
    }
}
