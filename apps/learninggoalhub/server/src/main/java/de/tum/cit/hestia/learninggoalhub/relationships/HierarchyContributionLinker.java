package de.tum.cit.hestia.learninggoalhub.relationships;

import de.tum.cit.hestia.learninggoalhub.goal.LearningGoal;
import de.tum.cit.hestia.learninggoalhub.goal.LearningGoalRepository;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyLevel;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Materializes CONTRIBUTES_TO relationships from each goal to the goals attached to its <em>non-module</em>
 * hierarchy ancestors (e.g. exercise→session). Module-level contributions are <em>not</em> created here:
 * linking every sub-goal to every module goal is a cartesian product that swamps the graph, so those edges
 * are instead emitted from the synthesiser's provenance (see
 * {@code ExtractionRunner#linkContributors}), where each sub-goal links only to the module outcome(s) it
 * actually serves. (A course whose module goals were extracted rather than synthesised therefore gets no
 * automatic session→module edges — an accepted limitation of that rare path.)
 */
@Service
public class HierarchyContributionLinker {

    private final LearningGoalRepository goalRepository;
    private final GoalRelationshipRepository relationshipRepository;

    public HierarchyContributionLinker(LearningGoalRepository goalRepository,
                                       GoalRelationshipRepository relationshipRepository) {
        this.goalRepository = goalRepository;
        this.relationshipRepository = relationshipRepository;
    }

    @Transactional
    public int linkCourse(Long courseId) {
        List<LearningGoal> goals = goalRepository.findByCourseIdAndHierarchyNodeIsNotNull(courseId);

        Map<Long, List<LearningGoal>> goalsByNodeId = new HashMap<>();
        for (LearningGoal g : goals) {
            goalsByNodeId.computeIfAbsent(g.getHierarchyNode().getId(), k -> new ArrayList<>()).add(g);
        }

        List<GoalRelationship> toSave = new ArrayList<>();
        for (LearningGoal source : goals) {
            HierarchyNode ancestor = source.getHierarchyNode().getParent();
            while (ancestor != null) {
                if (ancestor.getLevel() == HierarchyLevel.MODULE) {
                    // Module contributions come from synthesis provenance, not the cartesian here.
                    ancestor = ancestor.getParent();
                    continue;
                }
                for (LearningGoal target : goalsByNodeId.getOrDefault(ancestor.getId(), List.of())) {
                    if (source.getId().equals(target.getId())) {
                        continue;
                    }
                    if (relationshipRepository.existsBySourceIdAndTargetIdAndType(
                            source.getId(), target.getId(), RelationshipType.CONTRIBUTES_TO)) {
                        continue;
                    }
                    toSave.add(new GoalRelationship(
                            source, target, RelationshipType.CONTRIBUTES_TO, 1.0, RelationshipOrigin.HIERARCHY));
                }
                ancestor = ancestor.getParent();
            }
        }

        if (!toSave.isEmpty()) {
            relationshipRepository.saveAll(toSave);
        }
        return toSave.size();
    }
}
