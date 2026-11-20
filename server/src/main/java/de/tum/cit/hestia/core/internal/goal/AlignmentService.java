package de.tum.cit.hestia.core.internal.goal;

import de.tum.cit.hestia.core.AlignmentApi;
import de.tum.cit.hestia.core.AlignmentLinkInfo;
import de.tum.cit.hestia.core.CurrentUser;
import de.tum.cit.hestia.core.DomainModule;
import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link AlignmentApi}: links between goals and the objects of the domain modules. */
@Service
@Transactional(readOnly = true)
class AlignmentService implements AlignmentApi {

    private final AlignmentLinkRepository links;
    private final GoalService goals;
    private final CurrentUser currentUser;
    private final Clock clock = Clock.systemUTC();

    AlignmentService(AlignmentLinkRepository links, GoalService goals, CurrentUser currentUser) {
        this.links = links;
        this.goals = goals;
        this.currentUser = currentUser;
    }

    @Override
    @Transactional
    public AlignmentLinkInfo link(UUID goalId, DomainModule module, String targetType, String targetId) {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(targetType, "targetType");
        Objects.requireNonNull(targetId, "targetId");
        CoreGoal goal = goals.readable(goalId);
        if (goal.getRemovedAt() != null) {
            throw new IllegalArgumentException("A removed goal cannot be linked");
        }
        CoreAlignmentLink link = links.findByGoalIdAndTargetModuleAndTargetTypeAndTargetId(goalId, module, targetType,
                        targetId)
                .orElseGet(() -> links.saveAndFlush(new CoreAlignmentLink(goal.getCourseId(), goalId, module,
                        targetType, targetId, goal.getCurrentVersion(), currentUser.userId(), clock.instant())));
        return info(link);
    }

    @Override
    @Transactional
    public void unlink(UUID goalId, DomainModule module, String targetType, String targetId) {
        links.findByGoalIdAndTargetModuleAndTargetTypeAndTargetId(goalId, module, targetType, targetId)
                .ifPresent(links::delete);
    }

    @Override
    @Transactional
    public void unlinkTarget(DomainModule module, String targetType, String targetId) {
        links.deleteTarget(module, targetType, targetId);
    }

    @Override
    @Transactional
    public void unlinkTargetInCourse(DomainModule module, String targetType, String targetId, UUID courseId) {
        links.deleteTargetInCourse(module, targetType, targetId, courseId);
    }

    @Override
    public List<AlignmentLinkInfo> goalsFor(DomainModule module, String targetType, String targetId) {
        return links.findByTargetModuleAndTargetTypeAndTargetIdOrderByCreatedAtAscIdAsc(module, targetType, targetId)
                .stream().map(AlignmentService::info).toList();
    }

    @Override
    public Map<String, List<AlignmentLinkInfo>> goalsForTargets(DomainModule module, String targetType,
            Collection<String> targetIds) {
        if (targetIds.isEmpty()) {
            return Map.of();
        }
        return links.findByTargetModuleAndTargetTypeAndTargetIdInOrderByCreatedAtAscIdAsc(module, targetType,
                        targetIds)
                .stream().map(AlignmentService::info)
                .collect(Collectors.groupingBy(AlignmentLinkInfo::targetId, LinkedHashMap::new, Collectors.toList()));
    }

    @Override
    public List<AlignmentLinkInfo> linksOfGoal(UUID goalId) {
        goals.readable(goalId);
        return links.findByGoalIdOrderByCreatedAtAscIdAsc(goalId).stream().map(AlignmentService::info).toList();
    }

    private static AlignmentLinkInfo info(CoreAlignmentLink l) {
        return new AlignmentLinkInfo(l.getId(), l.getCourseId(), l.getGoalId(), l.getTargetModule(), l.getTargetType(),
                l.getTargetId(), l.getGoalVersionSeen(), l.getReviewState(), l.getReviewReason(), l.getCreatedAt());
    }
}
