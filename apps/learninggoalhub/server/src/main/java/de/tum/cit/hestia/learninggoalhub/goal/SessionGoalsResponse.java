package de.tum.cit.hestia.learninggoalhub.goal;

import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyLevel;
import java.util.List;

/** One hierarchy node (module/session/exercise) and its goals; all-null node fields = ungrouped. */
public record SessionGoalsResponse(Long nodeId,
                                   HierarchyLevel level,
                                   String label,
                                   List<LearningGoalResponse> goals) {
}
