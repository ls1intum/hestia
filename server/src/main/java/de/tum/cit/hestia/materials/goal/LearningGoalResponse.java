package de.tum.cit.hestia.materials.goal;

import de.tum.cit.hestia.core.GoalKind;
import de.tum.cit.hestia.core.GoalOrigin;
import de.tum.cit.hestia.core.GoalRole;
import de.tum.cit.hestia.core.GoalStatus;
import de.tum.cit.hestia.materials.hierarchy.HierarchyPath;
import de.tum.cit.hestia.materials.taxonomy.BloomLevel;
import de.tum.cit.hestia.materials.taxonomy.SoloLevel;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

public record LearningGoalResponse(UUID id,
                                   String text,
                                   String originalText,
                                   String shortLabel,
                                   GoalKind kind,
                                   GoalRole role,
                                   GoalStatus status,
                                   GoalOrigin origin,
                                   GoalCreationProvenance creationProvenance,
                                   HierarchyPath hierarchy,
                                   BloomLevel bloomLevel,
                                   SoloLevel soloLevel,
                                   Integer lectureOrder,
                                   OffsetDateTime createdAt,
                                   List<GoalSourceResponse> sources,
                                   List<GoalRelationshipResponse> relationships,
                                   @Schema(nullable = true) ExamTaskResponse examTask) {
    public static LearningGoalResponse from(PlacedGoal g,
                                            List<GoalSourceResponse> sources,
                                            List<GoalRelationshipResponse> relationships) {
        HierarchyPath hierarchy = g.hierarchyNode() == null
                ? null
                : HierarchyPath.from(g.hierarchyNode());
        return new LearningGoalResponse(
                g.id(),
                g.text(),
                g.originalText(),
                g.shortLabel(),
                g.kind(),
                g.role(),
                g.status(),
                g.origin(),
                g.creationProvenance(),
                hierarchy,
                g.bloomLevel(),
                g.soloLevel(),
                g.lectureOrder(),
                g.info().createdAt().atOffset(ZoneOffset.UTC),
                sources,
                relationships,
                g.examBlock() == null ? null : ExamTaskResponse.from(g.examBlock()));
    }
}
