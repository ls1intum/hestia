package de.tum.cit.hestia.learninggoalhub.goal;

import de.tum.cit.hestia.learninggoalhub.relationships.GoalRelationship;
import de.tum.cit.hestia.learninggoalhub.relationships.RelationshipOrigin;
import de.tum.cit.hestia.learninggoalhub.relationships.RelationshipType;
import java.util.Comparator;

public record GoalRelationshipResponse(RelationshipType type,
                                       Long targetGoalId,
                                       String targetText,
                                       double confidence,
                                       RelationshipOrigin origin) {
    /** Natural enum order matches the CSV export's CONTRIBUTES_TO → PREREQUISITE_OF → OVERLAPS_WITH grouping. */
    static final Comparator<GoalRelationshipResponse> ORDER =
            Comparator.comparing(GoalRelationshipResponse::type)
                    .thenComparing(GoalRelationshipResponse::targetText);

    static GoalRelationshipResponse from(GoalRelationship r) {
        return new GoalRelationshipResponse(
                r.getType(),
                r.getTarget().getId(),
                r.getTarget().getText(),
                r.getConfidence(),
                r.getOrigin());
    }
}
