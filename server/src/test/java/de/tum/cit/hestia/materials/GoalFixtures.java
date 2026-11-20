package de.tum.cit.hestia.materials;

import de.tum.cit.hestia.core.DomainModule;
import de.tum.cit.hestia.core.GoalKind;
import de.tum.cit.hestia.core.GoalOrigin;
import de.tum.cit.hestia.core.GoalRole;
import de.tum.cit.hestia.core.GoalStatus;
import de.tum.cit.hestia.core.LearningGoalApi;
import de.tum.cit.hestia.core.NewGoal;
import de.tum.cit.hestia.materials.goal.GoalCreationProvenance;
import de.tum.cit.hestia.materials.goal.NewPlacedGoal;
import de.tum.cit.hestia.materials.goal.PlacedGoal;
import de.tum.cit.hestia.materials.goal.PlacedGoals;
import de.tum.cit.hestia.materials.goal.RelationshipOrigin;
import de.tum.cit.hestia.materials.goal.RelationshipType;
import de.tum.cit.hestia.materials.hierarchy.HierarchyNode;
import java.util.UUID;

/**
 * Creates goals of {@code core} for the materials tests, as the default test user (the test thread
 * is logged in as that user, see {@link TestTenant}); the defaults are those of a freshly extracted
 * goal: explicit, pending, origin EXTRACTED.
 */
public final class GoalFixtures {

    private final UUID courseId;
    private final String text;
    private GoalKind kind = GoalKind.EXPLICIT;
    private GoalRole role;
    private GoalOrigin origin = GoalOrigin.EXTRACTED;
    private GoalStatus status = GoalStatus.PENDING;
    private GoalCreationProvenance provenance;
    private String bloomLevel;
    private String soloLevel;
    private String shortLabel;
    private HierarchyNode node;
    private Integer lectureOrder;

    private GoalFixtures(UUID courseId, String text) {
        this.courseId = courseId;
        this.text = text;
    }

    public static GoalFixtures goal(UUID courseId, String text) {
        return new GoalFixtures(courseId, text);
    }

    public GoalFixtures kind(GoalKind value) {
        this.kind = value;
        return this;
    }

    public GoalFixtures role(GoalRole value) {
        this.role = value;
        return this;
    }

    public GoalFixtures origin(GoalOrigin value) {
        this.origin = value;
        return this;
    }

    public GoalFixtures status(GoalStatus value) {
        this.status = value;
        return this;
    }

    public GoalFixtures provenance(GoalCreationProvenance value) {
        this.provenance = value;
        return this;
    }

    public GoalFixtures bloom(String value) {
        this.bloomLevel = value;
        return this;
    }

    public GoalFixtures solo(String value) {
        this.soloLevel = value;
        return this;
    }

    public GoalFixtures label(String value) {
        this.shortLabel = value;
        return this;
    }

    public GoalFixtures node(HierarchyNode value) {
        this.node = value;
        return this;
    }

    public GoalFixtures lectureOrder(Integer value) {
        this.lectureOrder = value;
        return this;
    }

    public PlacedGoal create() {
        NewGoal goal = new NewGoal(text, shortLabel, kind, role, bloomLevel, soloLevel, status, origin,
                provenance == null ? null : provenance.name(), DomainModule.MATERIALS);
        return TestTenant.bean(PlacedGoals.class).create(courseId, new NewPlacedGoal(goal, node, null, lectureOrder));
    }

    /** {@code child} contributes to {@code parent}, as the topic tree stores it. */
    public static void contributes(PlacedGoal child, PlacedGoal parent) {
        TestTenant.bean(LearningGoalApi.class).relate(child.id(), parent.id(),
                RelationshipType.CONTRIBUTES_TO.name(), 1.0, RelationshipOrigin.HIERARCHY.name());
    }

    /** The goals of the course that are not removed, with their placements. */
    public static java.util.List<PlacedGoal> goals(UUID courseId) {
        return TestTenant.bean(PlacedGoals.class).list(courseId);
    }

    /** One goal (removed ones too), with its placement. */
    public static PlacedGoal goal(UUID goalId) {
        return TestTenant.bean(PlacedGoals.class).wrap(java.util.List.of(TestTenant.bean(LearningGoalApi.class).goal(goalId)))
                .get(0);
    }

    /** One goal that is not removed, or empty. */
    public static java.util.Optional<PlacedGoal> find(UUID courseId, UUID goalId) {
        return TestTenant.bean(PlacedGoals.class).find(courseId, goalId);
    }

    /** The relationships whose target is the goal. */
    public static java.util.List<de.tum.cit.hestia.core.GoalRelationInfo> edgesInto(UUID goalId) {
        LearningGoalApi api = TestTenant.bean(LearningGoalApi.class);
        return api.relationships(api.goal(goalId).courseId()).stream()
                .filter(edge -> edge.targetGoalId().equals(goalId)).toList();
    }

    /** The relationships whose source is the goal. */
    public static java.util.List<de.tum.cit.hestia.core.GoalRelationInfo> edgesFrom(UUID goalId) {
        LearningGoalApi api = TestTenant.bean(LearningGoalApi.class);
        return api.relationships(api.goal(goalId).courseId()).stream()
                .filter(edge -> edge.sourceGoalId().equals(goalId)).toList();
    }

    /** The relationships whose source is one of the goals. */
    public static java.util.List<de.tum.cit.hestia.core.GoalRelationInfo> edgesFromAny(
            java.util.Collection<UUID> goalIds) {
        return goalIds.stream().flatMap(id -> edgesFrom(id).stream()).toList();
    }
}
