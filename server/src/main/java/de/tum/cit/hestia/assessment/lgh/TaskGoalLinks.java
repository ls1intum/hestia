package de.tum.cit.hestia.assessment.lgh;

import de.tum.cit.hestia.core.AlignmentApi;
import de.tum.cit.hestia.core.AlignmentLinkInfo;
import de.tum.cit.hestia.core.DomainModule;
import de.tum.cit.hestia.core.GoalInfo;
import de.tum.cit.hestia.core.LearningGoalApi;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The learning goals of exam tasks, which live in {@code core} as alignment links (target type
 * {@value #TARGET_TYPE}). Goal texts are read from {@code core} each time, so a goal renamed there
 * shows its new wording here without any import.
 */
@Component
public class TaskGoalLinks {

    public static final String TARGET_TYPE = "TASK";

    private final AlignmentApi alignment;
    private final LearningGoalApi goals;

    public TaskGoalLinks(AlignmentApi alignment, LearningGoalApi goals) {
        this.alignment = alignment;
        this.goals = goals;
    }

    /** The current (not removed) goals the caller can read, per task; tasks without goals are missing. */
    public Map<UUID, List<GoalInfo>> goalsOf(Collection<UUID> taskIds) {
        if (taskIds.isEmpty()) return Map.of();
        Map<String, List<AlignmentLinkInfo>> links = alignment.goalsForTargets(
            DomainModule.ASSESSMENT, TARGET_TYPE, taskIds.stream().map(UUID::toString).toList());
        Set<UUID> goalIds = new LinkedHashSet<>();
        links.values().forEach(l -> l.forEach(link -> goalIds.add(link.goalId())));
        Map<UUID, GoalInfo> byId = new HashMap<>();
        for (GoalInfo g : goals.goalsById(goalIds)) {
            if (!g.removed()) byId.put(g.id(), g);
        }
        Map<UUID, List<GoalInfo>> out = new HashMap<>();
        for (UUID taskId : taskIds) {
            List<GoalInfo> list = new ArrayList<>();
            for (AlignmentLinkInfo link : links.getOrDefault(taskId.toString(), List.of())) {
                GoalInfo g = byId.get(link.goalId());
                if (g != null) list.add(g);
            }
            if (!list.isEmpty()) out.put(taskId, list);
        }
        return out;
    }

    public void link(UUID goalId, UUID taskId) {
        alignment.link(goalId, DomainModule.ASSESSMENT, TARGET_TYPE, taskId.toString());
    }

    /** Removes every link of the tasks, for tasks that are deleted or whose content is retracted. */
    public void unlinkAll(Collection<UUID> taskIds) {
        for (UUID taskId : taskIds) {
            alignment.unlinkTarget(DomainModule.ASSESSMENT, TARGET_TYPE, taskId.toString());
        }
    }

    /** Removes the links of the tasks to goals of one course (the exam moves out of that course). */
    public void unlinkInCourse(Collection<UUID> taskIds, UUID courseId) {
        for (UUID taskId : taskIds) {
            alignment.unlinkTargetInCourse(DomainModule.ASSESSMENT, TARGET_TYPE, taskId.toString(), courseId);
        }
    }

    /** The ids of the goals linked to a task, including removed ones (links to them stay in core). */
    public List<UUID> linkedGoalIds(UUID taskId) {
        return alignment.goalsFor(DomainModule.ASSESSMENT, TARGET_TYPE, taskId.toString()).stream()
            .map(AlignmentLinkInfo::goalId).toList();
    }
}
