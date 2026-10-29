package de.tum.cit.hestia.learninggoalhub.goal;

import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyPath;
import de.tum.cit.hestia.learninggoalhub.taxonomy.BloomLevel;
import de.tum.cit.hestia.learninggoalhub.taxonomy.SoloLevel;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

public record LearningGoalResponse(Long id,
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
    public static LearningGoalResponse from(LearningGoal g,
                                            List<GoalSourceResponse> sources,
                                            List<GoalRelationshipResponse> relationships) {
        HierarchyPath hierarchy = g.getHierarchyNode() == null
                ? null
                : HierarchyPath.from(g.getHierarchyNode());
        return new LearningGoalResponse(
                g.getId(),
                g.getText(),
                g.getOriginalText(),
                g.getShortLabel(),
                g.getKind(),
                g.getRole(),
                g.getStatus(),
                g.getOrigin(),
                g.getCreationProvenance(),
                hierarchy,
                g.getBloomLevel(),
                g.getSoloLevel(),
                g.getLectureOrder(),
                g.getCreatedAt(),
                sources,
                relationships,
                g.getExamBlock() == null ? null : ExamTaskResponse.from(g.getExamBlock()));
    }
}
