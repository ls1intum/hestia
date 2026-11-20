package de.tum.cit.hestia.core.internal.goal;

import de.tum.cit.hestia.core.AlignmentApi;
import de.tum.cit.hestia.core.AlignmentLinkInfo;
import de.tum.cit.hestia.core.AlignmentLinkNotFoundException;
import de.tum.cit.hestia.core.AlignmentReview;
import de.tum.cit.hestia.core.CourseAccess;
import de.tum.cit.hestia.core.CurrentUser;
import de.tum.cit.hestia.core.DomainModule;
import de.tum.cit.hestia.core.spi.AlignmentTargetLabels;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link AlignmentApi}: links between goals and the objects of the domain modules, and the review
 * marks {@link ReviewMarks} sets on them. Making or removing a single link needs {@code EDITOR} in
 * the goal's course; reading links returns only links of courses the user is a member of.
 */
@Service
@Transactional(readOnly = true)
class AlignmentService implements AlignmentApi {

    private final AlignmentLinkRepository links;
    private final GoalService goalService;
    private final GoalRepository goals;
    private final GoalVersionRepository versions;
    private final CourseAccess access;
    private final CurrentUser currentUser;
    private final ObjectProvider<AlignmentTargetLabels> labels;
    private final Clock clock = Clock.systemUTC();

    AlignmentService(AlignmentLinkRepository links, GoalService goalService, GoalRepository goals,
            GoalVersionRepository versions, CourseAccess access, CurrentUser currentUser,
            ObjectProvider<AlignmentTargetLabels> labels) {
        this.links = links;
        this.goalService = goalService;
        this.goals = goals;
        this.versions = versions;
        this.access = access;
        this.currentUser = currentUser;
        this.labels = labels;
    }

    @Override
    @Transactional
    public AlignmentLinkInfo link(UUID goalId, DomainModule module, String targetType, String targetId) {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(targetType, "targetType");
        Objects.requireNonNull(targetId, "targetId");
        CoreGoal goal = goalService.readable(goalId);
        access.requireWrite(goal.getCourseId());
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
                .ifPresent(link -> {
                    access.requireWrite(link.getCourseId());
                    links.delete(link);
                });
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
        return readableInfos(
                links.findByTargetModuleAndTargetTypeAndTargetIdOrderByCreatedAtAscIdAsc(module, targetType, targetId));
    }

    @Override
    public Map<String, List<AlignmentLinkInfo>> goalsForTargets(DomainModule module, String targetType,
            Collection<String> targetIds) {
        if (targetIds.isEmpty()) {
            return Map.of();
        }
        return readableInfos(links.findByTargetModuleAndTargetTypeAndTargetIdInOrderByCreatedAtAscIdAsc(module,
                        targetType, targetIds))
                .stream()
                .collect(Collectors.groupingBy(AlignmentLinkInfo::targetId, LinkedHashMap::new, Collectors.toList()));
    }

    @Override
    public List<AlignmentLinkInfo> linksOfGoal(UUID goalId) {
        goalService.readable(goalId);
        return links.findByGoalIdOrderByCreatedAtAscIdAsc(goalId).stream().map(AlignmentService::info).toList();
    }

    @Override
    public List<AlignmentReview> openReviews(UUID courseId) {
        access.requireRead(courseId);
        List<CoreAlignmentLink> open =
                links.findByCourseIdAndReviewStateOrderByCreatedAtAscIdAsc(courseId, CoreAlignmentLink.REVIEW);
        if (open.isEmpty()) {
            return List.of();
        }
        Map<UUID, CoreGoal> goalsById = goals.findAllById(open.stream().map(CoreAlignmentLink::getGoalId)
                        .collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(CoreGoal::getId, Function.identity()));
        Set<CoreGoalVersion.Key> keys = new HashSet<>();
        for (CoreAlignmentLink l : open) {
            keys.add(new CoreGoalVersion.Key(l.getGoalId(), l.getGoalVersionSeen()));
            keys.add(new CoreGoalVersion.Key(l.getGoalId(), goalsById.get(l.getGoalId()).getCurrentVersion()));
        }
        Map<CoreGoalVersion.Key, String> texts = new HashMap<>();
        versions.findAllById(keys).forEach(v -> texts.put(new CoreGoalVersion.Key(v.getGoalId(), v.getVersion()),
                v.getText()));
        Map<DomainModule, Map<String, Map<String, String>>> targetLabels = labelsFor(open);
        List<AlignmentReview> out = new ArrayList<>(open.size());
        for (CoreAlignmentLink l : open) {
            CoreGoal goal = goalsById.get(l.getGoalId());
            String label = targetLabels.getOrDefault(l.getTargetModule(), Map.of())
                    .getOrDefault(l.getTargetType(), Map.of()).get(l.getTargetId());
            out.add(new AlignmentReview(l.getId(), l.getCourseId(), l.getGoalId(), l.getTargetModule(),
                    l.getTargetType(), l.getTargetId(), label, l.getReviewReason(), l.getGoalVersionSeen(),
                    goal.getCurrentVersion(), texts.get(new CoreGoalVersion.Key(l.getGoalId(), l.getGoalVersionSeen())),
                    texts.get(new CoreGoalVersion.Key(l.getGoalId(), goal.getCurrentVersion())),
                    goal.getRemovedAt() != null, l.getCreatedAt()));
        }
        return out;
    }

    @Override
    @Transactional
    public AlignmentLinkInfo confirm(UUID linkId) {
        return confirm(null, linkId);
    }

    /** {@link #confirm(UUID)} for a link that must belong to the given course (any course if null). */
    @Transactional
    AlignmentLinkInfo confirm(UUID courseId, UUID linkId) {
        CoreAlignmentLink link = links.findById(linkId).orElseThrow(() -> new AlignmentLinkNotFoundException(linkId));
        if ((courseId != null && !courseId.equals(link.getCourseId())) || !access.canRead(link.getCourseId())) {
            throw new AlignmentLinkNotFoundException(linkId);
        }
        access.requireWrite(link.getCourseId());
        CoreGoal goal = goals.findById(link.getGoalId()).orElseThrow();
        link.confirm(goal.getCurrentVersion());
        links.flush();
        return info(link);
    }

    // --- helpers

    /** The links whose course the user can read, in the given order. */
    private List<AlignmentLinkInfo> readableInfos(List<CoreAlignmentLink> rows) {
        Map<UUID, Boolean> readable = new HashMap<>();
        return rows.stream()
                .filter(l -> readable.computeIfAbsent(l.getCourseId(), access::canRead))
                .map(AlignmentService::info)
                .toList();
    }

    /** Labels of the linked objects from their modules, by module, target type and target id. */
    private Map<DomainModule, Map<String, Map<String, String>>> labelsFor(List<CoreAlignmentLink> rows) {
        Map<DomainModule, Map<String, Set<String>>> wanted = new HashMap<>();
        rows.forEach(l -> wanted.computeIfAbsent(l.getTargetModule(), m -> new HashMap<>())
                .computeIfAbsent(l.getTargetType(), t -> new HashSet<>()).add(l.getTargetId()));
        Map<DomainModule, Map<String, Map<String, String>>> out = new HashMap<>();
        labels.orderedStream().filter(p -> wanted.containsKey(p.module())).forEach(p ->
                wanted.get(p.module()).forEach((type, ids) -> out.computeIfAbsent(p.module(), m -> new HashMap<>())
                        .put(type, p.labels(type, ids))));
        return out;
    }

    private static AlignmentLinkInfo info(CoreAlignmentLink l) {
        return new AlignmentLinkInfo(l.getId(), l.getCourseId(), l.getGoalId(), l.getTargetModule(), l.getTargetType(),
                l.getTargetId(), l.getGoalVersionSeen(), l.getReviewState(), l.getReviewReason(), l.getCreatedAt());
    }
}
