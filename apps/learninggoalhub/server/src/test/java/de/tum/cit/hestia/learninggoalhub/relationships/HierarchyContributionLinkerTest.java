package de.tum.cit.hestia.learninggoalhub.relationships;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import de.tum.cit.hestia.learninggoalhub.TestcontainersConfiguration;
import de.tum.cit.hestia.learninggoalhub.course.Course;
import de.tum.cit.hestia.learninggoalhub.course.CourseRepository;
import de.tum.cit.hestia.learninggoalhub.goal.GoalKind;
import de.tum.cit.hestia.learninggoalhub.goal.LearningGoal;
import de.tum.cit.hestia.learninggoalhub.goal.LearningGoalRepository;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyLevel;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNode;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNodeRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, HierarchyContributionLinker.class})
class HierarchyContributionLinkerTest {

    @Autowired
    private CourseRepository courseRepository;

    @Autowired
    private HierarchyNodeRepository hierarchyNodeRepository;

    @Autowired
    private LearningGoalRepository goalRepository;

    @Autowired
    private GoalRelationshipRepository relationshipRepository;

    @Autowired
    private HierarchyContributionLinker linker;

    @Test
    void linksToNonModuleAncestorsButSkipsModuleTargets() {
        Course course = courseRepository.save(new Course("Software Engineering"));

        HierarchyNode module = hierarchyNodeRepository.save(
                new HierarchyNode(course, null, HierarchyLevel.MODULE, "Software Engineering"));
        HierarchyNode session = hierarchyNodeRepository.save(
                new HierarchyNode(course, module, HierarchyLevel.SESSION, "Session 3: Testing"));
        HierarchyNode exercise = hierarchyNodeRepository.save(
                new HierarchyNode(course, session, HierarchyLevel.EXERCISE, "Exercise 3.1: TDD kata"));

        LearningGoal moduleGoal = save(course, "Understand software engineering principles.", module);
        LearningGoal sessionGoal = save(course, "Apply unit testing techniques.", session);
        LearningGoal exerciseGoal = save(course, "Practise red-green-refactor cycle.", exercise);

        int created = linker.linkCourse(course.getId());

        // Only exercise→session survives. exercise→module and session→module are NOT created here —
        // module-level contributions come from synthesis provenance, not this structural linker.
        assertThat(created).isEqualTo(1);

        List<GoalRelationship> all = relationshipRepository.findAll();
        assertThat(all)
                .extracting(r -> r.getSource().getId(), r -> r.getTarget().getId(), GoalRelationship::getType,
                        GoalRelationship::getOrigin)
                .containsExactly(
                        tuple(exerciseGoal.getId(), sessionGoal.getId(), RelationshipType.CONTRIBUTES_TO,
                                RelationshipOrigin.HIERARCHY));
        assertThat(all).noneMatch(r -> r.getTarget().getId().equals(moduleGoal.getId()));
        assertThat(all).allMatch(r -> r.getConfidence() == 1.0);
    }

    @Test
    void isIdempotentOnSecondRun() {
        Course course = courseRepository.save(new Course("SE"));
        HierarchyNode module = hierarchyNodeRepository.save(
                new HierarchyNode(course, null, HierarchyLevel.MODULE, "SE"));
        HierarchyNode session = hierarchyNodeRepository.save(
                new HierarchyNode(course, module, HierarchyLevel.SESSION, "S1"));
        HierarchyNode exercise = hierarchyNodeRepository.save(
                new HierarchyNode(course, session, HierarchyLevel.EXERCISE, "E1"));

        save(course, "session goal", session);
        save(course, "exercise goal", exercise);

        int first = linker.linkCourse(course.getId());
        int second = linker.linkCourse(course.getId());

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(relationshipRepository.count()).isEqualTo(1);
    }

    @Test
    void skipsGoalsWithoutHierarchyNode() {
        Course course = courseRepository.save(new Course("SE"));
        save(course, "untethered goal", null);

        int created = linker.linkCourse(course.getId());

        assertThat(created).isZero();
        assertThat(relationshipRepository.count()).isZero();
    }

    private LearningGoal save(Course course, String text, HierarchyNode node) {
        LearningGoal g = new LearningGoal(course, text, GoalKind.EXPLICIT);
        if (node != null) {
            g.setHierarchyNode(node);
        }
        return goalRepository.save(g);
    }
}
