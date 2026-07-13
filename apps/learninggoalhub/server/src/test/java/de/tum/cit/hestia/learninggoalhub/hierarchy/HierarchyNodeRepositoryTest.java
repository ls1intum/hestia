package de.tum.cit.hestia.learninggoalhub.hierarchy;

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
@Import(TestcontainersConfiguration.class)
class HierarchyNodeRepositoryTest {

    @Autowired
    private CourseRepository courseRepository;

    @Autowired
    private HierarchyNodeRepository hierarchyRepository;

    @Autowired
    private LearningGoalRepository goalRepository;

    @Test
    void persistsModuleSessionExerciseTree() {
        Course course = courseRepository.save(new Course("Software Engineering"));

        HierarchyNode module = hierarchyRepository.save(
                new HierarchyNode(course, null, HierarchyLevel.MODULE, "Software Engineering"));
        HierarchyNode session = hierarchyRepository.save(
                new HierarchyNode(course, module, HierarchyLevel.SESSION, "Session 3: Testing"));
        HierarchyNode exercise = hierarchyRepository.save(
                new HierarchyNode(course, session, HierarchyLevel.EXERCISE, "Exercise 3.2: TDD Kata"));

        List<HierarchyNode> nodes = hierarchyRepository.findByCourseId(course.getId());

        assertThat(nodes).hasSize(3);
        assertThat(session.getParent().getId()).isEqualTo(module.getId());
        assertThat(exercise.getParent().getId()).isEqualTo(session.getId());
        assertThat(module.getParent()).isNull();
    }

    @Test
    void learningGoalCanReferenceHierarchyNode() {
        Course course = courseRepository.save(new Course("Software Engineering"));
        HierarchyNode session = hierarchyRepository.save(
                new HierarchyNode(course, null, HierarchyLevel.SESSION, "Session 1"));

        LearningGoal goal = new LearningGoal(course, "Apply TDD.", GoalKind.EXPLICIT);
        goal.setHierarchyNode(session);
        LearningGoal saved = goalRepository.save(goal);

        LearningGoal reloaded = goalRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getHierarchyNode()).isNotNull();
        assertThat(reloaded.getHierarchyNode().getId()).isEqualTo(session.getId());
        assertThat(reloaded.getHierarchyNode().getLabel()).isEqualTo("Session 1");
    }
}
