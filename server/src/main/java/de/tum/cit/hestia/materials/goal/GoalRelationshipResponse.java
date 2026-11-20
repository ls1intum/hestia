package de.tum.cit.hestia.materials.goal;

import de.tum.cit.hestia.core.GoalRelationInfo;
import java.util.Comparator;
import java.util.UUID;

public record GoalRelationshipResponse(RelationshipType type,
                                       UUID targetGoalId,
                                       String targetText,
                                       double confidence,
                                       RelationshipOrigin origin) {
    /** Natural enum order matches the CSV export's CONTRIBUTES_TO → PREREQUISITE_OF → OVERLAPS_WITH grouping. */
    static final Comparator<GoalRelationshipResponse> ORDER =
            Comparator.comparing(GoalRelationshipResponse::type)
                    .thenComparing(GoalRelationshipResponse::targetText);

    static GoalRelationshipResponse from(GoalRelationInfo r, String targetText) {
        return new GoalRelationshipResponse(
                RelationshipType.valueOf(r.type()),
                r.targetGoalId(),
                targetText,
                r.confidence(),
                RelationshipOrigin.valueOf(r.origin()));
    }
}
