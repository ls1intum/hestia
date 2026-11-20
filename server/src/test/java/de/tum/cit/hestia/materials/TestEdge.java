package de.tum.cit.hestia.materials;

import de.tum.cit.hestia.core.GoalRelationInfo;
import de.tum.cit.hestia.materials.goal.RelationshipOrigin;
import de.tum.cit.hestia.materials.goal.RelationshipType;
import java.util.UUID;

/** A goal relationship as the materials tests build and read it; stored by {@link TestGoalRepository.Edges}. */
public class TestEdge {

    private final TestGoal source;
    private final TestGoal target;
    private final RelationshipType type;
    private final double confidence;
    private final RelationshipOrigin origin;

    public TestEdge(TestGoal source, TestGoal target, RelationshipType type, double confidence,
                    RelationshipOrigin origin) {
        this.source = source;
        this.target = target;
        this.type = type;
        this.confidence = confidence;
        this.origin = origin;
    }

    static TestEdge of(GoalRelationInfo info) {
        return new TestEdge(goal(info.sourceGoalId()), goal(info.targetGoalId()),
                RelationshipType.valueOf(info.type()), info.confidence(), RelationshipOrigin.valueOf(info.origin()));
    }

    private static TestGoal goal(UUID id) {
        return TestGoal.of(GoalFixtures.goal(id));
    }

    void adopt(GoalRelationInfo info) {
    }

    public TestGoal getSource() {
        return source;
    }

    public TestGoal getTarget() {
        return target;
    }

    public RelationshipType getType() {
        return type;
    }

    public double getConfidence() {
        return confidence;
    }

    public RelationshipOrigin getOrigin() {
        return origin;
    }
}
