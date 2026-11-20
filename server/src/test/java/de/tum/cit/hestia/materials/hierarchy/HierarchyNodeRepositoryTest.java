package de.tum.cit.hestia.materials.hierarchy;

import de.tum.cit.hestia.materials.TestTenant;
import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.hestia.materials.MaterialsTestBase;
import de.tum.cit.hestia.materials.MaterialsTestConfiguration;
import de.tum.cit.hestia.materials.GoalFixtures;
import de.tum.cit.hestia.materials.goal.PlacedGoal;
import java.util.UUID;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@org.springframework.transaction.annotation.Transactional
@Import(MaterialsTestConfiguration.class)
class HierarchyNodeRepositoryTest extends MaterialsTestBase {

    @Autowired
    private HierarchyNodeRepository hierarchyRepository;

    @Test
    void persistsModuleSessionExerciseTree() {
        UUID course = TestTenant.course("Software Engineering");

        HierarchyNode module = hierarchyRepository.save(
                new HierarchyNode(course, null, HierarchyLevel.MODULE, "Software Engineering"));
        HierarchyNode session = hierarchyRepository.save(
                new HierarchyNode(course, module, HierarchyLevel.SESSION, "Session 3: Testing"));
        HierarchyNode exercise = hierarchyRepository.save(
                new HierarchyNode(course, session, HierarchyLevel.EXERCISE, "Exercise 3.2: TDD Kata"));

        List<HierarchyNode> nodes = hierarchyRepository.findByCourseId(course);

        assertThat(nodes).hasSize(3);
        assertThat(session.getParent().getId()).isEqualTo(module.getId());
        assertThat(exercise.getParent().getId()).isEqualTo(session.getId());
        assertThat(module.getParent()).isNull();
    }

    @Test
    void learningGoalCanReferenceHierarchyNode() {
        UUID course = TestTenant.course("Software Engineering");
        HierarchyNode session = hierarchyRepository.save(
                new HierarchyNode(course, null, HierarchyLevel.SESSION, "Session 1"));

        PlacedGoal saved = GoalFixtures.goal(course, "Apply TDD.").node(session).create();

        PlacedGoal reloaded = GoalFixtures.goal(saved.id());
        assertThat(reloaded.hierarchyNode()).isNotNull();
        assertThat(reloaded.hierarchyNode().getId()).isEqualTo(session.getId());
        assertThat(reloaded.hierarchyNode().getLabel()).isEqualTo("Session 1");
    }
}
