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
@Import(TestcontainersConfiguration.class)
class GoalRelationshipRepositoryTest {

    @Autowired
    private CourseRepository courseRepository;

    @Autowired
    private LearningGoalRepository goalRepository;

    @Autowired
    private GoalRelationshipRepository relationshipRepository;

    @Test
    void relationshipsCanBePersistedAndQueriedBySource() {
        Course course = courseRepository.save(new Course("Software Engineering"));
        LearningGoal a = goalRepository.save(new LearningGoal(course, "Understand TDD basics.", GoalKind.EXPLICIT));
        LearningGoal b = goalRepository.save(new LearningGoal(course, "Apply red-green-refactor.", GoalKind.EXPLICIT));
        LearningGoal c = goalRepository.save(new LearningGoal(course, "Design test suites.", GoalKind.IMPLICIT));

        relationshipRepository.save(new GoalRelationship(a, b, RelationshipType.PREREQUISITE_OF, 0.9, RelationshipOrigin.LLM));
        relationshipRepository.save(new GoalRelationship(a, c, RelationshipType.CONTRIBUTES_TO, 1.0, RelationshipOrigin.HIERARCHY));

        List<GoalRelationship> fromA = relationshipRepository.findBySourceId(a.getId());

        assertThat(fromA).hasSize(2);
        assertThat(fromA).extracting(r -> r.getTarget().getText())
                .containsExactlyInAnyOrder("Apply red-green-refactor.", "Design test suites.");
        assertThat(fromA).extracting(GoalRelationship::getType)
                .containsExactlyInAnyOrder(RelationshipType.PREREQUISITE_OF, RelationshipType.CONTRIBUTES_TO);
    }
}
