package de.tum.cit.hestia.materials;

import de.tum.cit.hestia.core.DomainModule;
import de.tum.cit.hestia.core.GoalChange;
import de.tum.cit.hestia.core.GoalOrigin;
import de.tum.cit.hestia.core.GoalRelationInfo;
import de.tum.cit.hestia.core.LearningGoalApi;
import de.tum.cit.hestia.core.NewGoal;
import de.tum.cit.hestia.materials.goal.NewPlacedGoal;
import de.tum.cit.hestia.materials.goal.PlacedGoal;
import de.tum.cit.hestia.materials.goal.PlacedGoals;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores and reads {@link TestGoal}s and {@link TestEdge}s through the goal API of {@code core} and
 * the materials placements, as the default test user (see {@link TestTenant}). It has the shape of the
 * repositories the tests used before goals moved to {@code core}.
 */
public class TestGoalRepository {

    private PlacedGoals placedGoals() {
        return TestTenant.bean(PlacedGoals.class);
    }

    private LearningGoalApi api() {
        return TestTenant.bean(LearningGoalApi.class);
    }

    /** Creates the goal, or stores what the setters changed on a goal that exists. */
    public TestGoal save(TestGoal goal) {
        if (goal.saved() == null) {
            NewGoal data = new NewGoal(goal.getText(), goal.getShortLabel(), goal.getKind(), goal.getRole(),
                    goal.getBloomLevel() == null ? null : goal.getBloomLevel().name(),
                    goal.getSoloLevel() == null ? null : goal.getSoloLevel().name(),
                    goal.getStatus(), goal.getOrigin(),
                    goal.getCreationProvenance() == null ? null : goal.getCreationProvenance().name(),
                    DomainModule.MATERIALS);
            goal.adopt(placedGoals().create(goal.getCourseId(),
                    new NewPlacedGoal(data, goal.getHierarchyNode(), null, goal.getLectureOrder())));
            return goal;
        }
        UUID id = goal.getId();
        GoalChange change = new GoalChange(goal.getText(), goal.getShortLabel(), goal.getKind(), goal.getRole(),
                goal.getBloomLevel() == null ? null : goal.getBloomLevel().name(),
                goal.getSoloLevel() == null ? null : goal.getSoloLevel().name());
        if (!change.equals(api().goal(id).asChange())) {
            api().update(id, change);
        }
        if (goal.getStatus() != api().goal(id).status()) {
            api().setStatus(id, goal.getStatus());
        }
        var placement = placedGoals().placementFor(id);
        placement.setHierarchyNode(goal.getHierarchyNode());
        placement.setLectureOrder(goal.getLectureOrder());
        TestTenant.bean(de.tum.cit.hestia.materials.goal.GoalPlacementRepository.class).saveAndFlush(placement);
        goal.adopt(placedGoals().require(goal.getCourseId(), id));
        return goal;
    }

    public TestGoal saveAndFlush(TestGoal goal) {
        return save(goal);
    }

    /** The goal unless it was removed. */
    public Optional<TestGoal> findById(UUID id) {
        try {
            PlacedGoal goal = GoalFixtures.goal(id);
            return goal.info().removed() ? Optional.empty() : Optional.of(TestGoal.of(goal));
        } catch (de.tum.cit.hestia.core.GoalNotFoundException e) {
            return Optional.empty();
        }
    }

    public boolean existsById(UUID id) {
        return findById(id).isPresent();
    }

    public List<TestGoal> findByCourseId(UUID courseId) {
        return placedGoals().list(courseId).stream().map(TestGoal::of).toList();
    }

    public List<TestGoal> findByCourseIdAndOriginIn(UUID courseId, Collection<GoalOrigin> origins) {
        return findByCourseId(courseId).stream().filter(g -> origins.contains(g.getOrigin())).toList();
    }

    /** Edges and their goals through the same API. */
    public static final class Edges {

        public TestEdge save(TestEdge edge) {
            GoalRelationInfo info = TestTenant.bean(LearningGoalApi.class).relate(edge.getSource().getId(),
                    edge.getTarget().getId(), edge.getType().name(), edge.getConfidence(), edge.getOrigin().name());
            edge.adopt(info);
            return edge;
        }

        public TestEdge saveAndFlush(TestEdge edge) {
            return save(edge);
        }

        public void flush() {
        }

        public List<TestEdge> findBySourceId(UUID goalId) {
            return GoalFixtures.edgesFrom(goalId).stream().map(TestEdge::of).toList();
        }

        public List<TestEdge> findBySourceIdIn(Collection<UUID> goalIds) {
            return GoalFixtures.edgesFromAny(goalIds).stream().map(TestEdge::of).toList();
        }

        public List<TestEdge> findByTargetId(UUID goalId) {
            return GoalFixtures.edgesInto(goalId).stream().map(TestEdge::of).toList();
        }
    }

    static boolean same(Object a, Object b) {
        return Objects.equals(a, b);
    }
}
