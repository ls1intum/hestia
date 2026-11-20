package de.tum.cit.hestia.core.internal.goal;

import de.tum.cit.hestia.core.CourseAccess;
import de.tum.cit.hestia.core.CurrentUser;
import de.tum.cit.hestia.core.GoalChange;
import de.tum.cit.hestia.core.GoalInfo;
import de.tum.cit.hestia.core.GoalNotFoundException;
import de.tum.cit.hestia.core.GoalRelationInfo;
import de.tum.cit.hestia.core.GoalStatus;
import de.tum.cit.hestia.core.GoalVersionInfo;
import de.tum.cit.hestia.core.LearningGoalApi;
import de.tum.cit.hestia.core.NewGoal;
import de.tum.cit.hestia.core.events.GoalChanged;
import de.tum.cit.hestia.core.events.GoalRemoved;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link LearningGoalApi}: goals, their versions and relationships. Course roles are checked on the
 * goal's course through {@link CourseAccess}; a goal in a course the user is no member of looks
 * like a missing goal. A new version or a removal publishes {@link GoalChanged} or
 * {@link GoalRemoved} inside the same transaction; {@link ReviewMarks} marks the goal's alignment
 * links for review on it.
 */
@Service
@Transactional(readOnly = true)
class GoalService implements LearningGoalApi {

    private final GoalRepository goals;
    private final GoalVersionRepository versions;
    private final GoalRelationshipRepository relationships;
    private final CourseAccess access;
    private final CurrentUser currentUser;
    private final ApplicationEventPublisher events;
    private final Clock clock = Clock.systemUTC();

    GoalService(GoalRepository goals, GoalVersionRepository versions, GoalRelationshipRepository relationships,
            CourseAccess access, CurrentUser currentUser, ApplicationEventPublisher events) {
        this.events = events;
        this.goals = goals;
        this.versions = versions;
        this.relationships = relationships;
        this.access = access;
        this.currentUser = currentUser;
    }

    @Override
    public List<GoalInfo> goals(UUID courseId) {
        access.requireRead(courseId);
        return infos(goals.findByCourseIdAndRemovedAtIsNullOrderByCreatedAtAscIdAsc(courseId));
    }

    @Override
    public GoalInfo goal(UUID goalId) {
        return infos(List.of(readable(goalId))).getFirst();
    }

    @Override
    public List<GoalInfo> goalsById(Collection<UUID> goalIds) {
        if (goalIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, Boolean> readableCourses = new HashMap<>();
        List<CoreGoal> found = goals.findAllById(new LinkedHashSet<>(goalIds)).stream()
                .filter(g -> readableCourses.computeIfAbsent(g.getCourseId(), access::canRead))
                .toList();
        return infos(found);
    }

    @Override
    @Transactional
    public GoalInfo create(UUID courseId, NewGoal goal) {
        return createAll(courseId, List.of(goal)).getFirst();
    }

    @Override
    @Transactional
    public List<GoalInfo> createAll(UUID courseId, List<NewGoal> newGoals) {
        access.requireWrite(courseId);
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        UUID userId = currentUser.userId();
        List<CoreGoal> created = new ArrayList<>();
        for (int i = 0; i < newGoals.size(); i++) {
            NewGoal goal = newGoals.get(i);
            // One microsecond apart (the column's precision), so "oldest first" keeps the given order.
            CoreGoal row = goals.save(new CoreGoal(courseId, goal.status(), goal.origin(), goal.creationProvenance(),
                    goal.createdVia(), userId, now.plus(i, java.time.temporal.ChronoUnit.MICROS)));
            created.add(row);
        }
        goals.flush();
        for (int i = 0; i < created.size(); i++) {
            NewGoal goal = newGoals.get(i);
            versions.save(new CoreGoalVersion(created.get(i).getId(), 1, new GoalChange(goal.text(), goal.shortLabel(),
                    goal.kind(), goal.role(), goal.bloomLevel(), goal.soloLevel()), userId, now));
        }
        versions.flush();
        return infos(created);
    }

    @Override
    @Transactional
    public GoalInfo update(UUID goalId, GoalChange change) {
        CoreGoal goal = writable(goalId);
        CoreGoalVersion current = versions.findById(new CoreGoalVersion.Key(goalId, goal.getCurrentVersion()))
                .orElseThrow();
        if (!current.sameContent(change)) {
            int next = goal.getCurrentVersion() + 1;
            versions.saveAndFlush(new CoreGoalVersion(goalId, next, change, currentUser.userId(), clock.instant()));
            goal.setCurrentVersion(next);
            goals.flush();
            events.publishEvent(new GoalChanged(goal.getOrgId(), goal.getCourseId(), goalId, next - 1, next));
        }
        return infos(List.of(goal)).getFirst();
    }

    @Override
    @Transactional
    public GoalInfo setStatus(UUID goalId, GoalStatus status) {
        CoreGoal goal = writable(goalId);
        goal.setStatus(Objects.requireNonNull(status, "status"));
        goals.flush();
        return infos(List.of(goal)).getFirst();
    }

    @Override
    @Transactional
    public void remove(UUID goalId) {
        removeAll(List.of(goalId));
    }

    @Override
    @Transactional
    public void removeAll(Collection<UUID> goalIds) {
        Set<UUID> ids = new HashSet<>(goalIds);
        if (ids.isEmpty()) {
            return;
        }
        Map<UUID, CoreGoal> found = new HashMap<>();
        goals.findAllById(ids).forEach(g -> found.put(g.getId(), g));
        Set<UUID> checked = new HashSet<>();
        for (UUID id : ids) {
            CoreGoal goal = found.get(id);
            if (goal == null || !access.canRead(goal.getCourseId())) {
                throw new GoalNotFoundException(id);
            }
            if (checked.add(goal.getCourseId())) {
                access.requireWrite(goal.getCourseId());
            }
        }
        Instant now = clock.instant();
        List<CoreGoal> removed = found.values().stream().filter(g -> g.getRemovedAt() == null).toList();
        removed.forEach(g -> g.setRemovedAt(now));
        goals.flush();
        relationships.deleteTouching(ids);
        removed.forEach(g -> events.publishEvent(
                new GoalRemoved(g.getOrgId(), g.getCourseId(), g.getId(), g.getCurrentVersion())));
    }

    @Override
    public List<GoalVersionInfo> history(UUID goalId) {
        readable(goalId);
        return versions.history(goalId).stream()
                .map(v -> new GoalVersionInfo(v.getGoalId(), v.getVersion(), v.getText(), v.getShortLabel(),
                        v.getKind(), v.getRole(), v.getBloomLevel(), v.getSoloLevel(), v.getChangedBy(),
                        v.getCreatedAt()))
                .toList();
    }

    @Override
    @Transactional
    public GoalRelationInfo relate(UUID sourceGoalId, UUID targetGoalId, String type, double confidence,
            String origin) {
        CoreGoal source = writable(sourceGoalId);
        CoreGoal target = readable(targetGoalId);
        if (!source.getCourseId().equals(target.getCourseId())) {
            throw new IllegalArgumentException("Related goals must belong to the same course");
        }
        if (source.getRemovedAt() != null || target.getRemovedAt() != null) {
            throw new IllegalArgumentException("A removed goal cannot be related");
        }
        CoreGoalRelationship row = relationships
                .findBySourceGoalIdAndTargetGoalIdAndType(sourceGoalId, targetGoalId, type)
                .orElseGet(() -> relationships.saveAndFlush(new CoreGoalRelationship(sourceGoalId, targetGoalId,
                        Objects.requireNonNull(type, "type"), confidence, Objects.requireNonNull(origin, "origin"),
                        clock.instant())));
        return relation(row);
    }

    @Override
    @Transactional
    public void unrelate(UUID relationshipId) {
        relationships.findById(relationshipId).ifPresent(r -> {
            writable(r.getSourceGoalId());
            relationships.delete(r);
        });
    }

    @Override
    public List<GoalRelationInfo> relationships(UUID courseId) {
        access.requireRead(courseId);
        return relationships.findByCourse(courseId).stream().map(GoalService::relation).toList();
    }

    // --- helpers

    /** The goal if the user may read its course; otherwise as if it did not exist. */
    CoreGoal readable(UUID goalId) {
        CoreGoal goal = goals.findById(goalId).orElseThrow(() -> new GoalNotFoundException(goalId));
        if (!access.canRead(goal.getCourseId())) {
            throw new GoalNotFoundException(goalId);
        }
        return goal;
    }

    private CoreGoal writable(UUID goalId) {
        CoreGoal goal = readable(goalId);
        access.requireWrite(goal.getCourseId());
        return goal;
    }

    private List<GoalInfo> infos(List<CoreGoal> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<CoreGoalVersion.Key, CoreGoalVersion> byKey = new HashMap<>();
        for (CoreGoalVersion v : versions.firstAndCurrent(rows.stream().map(CoreGoal::getId).toList())) {
            byKey.put(new CoreGoalVersion.Key(v.getGoalId(), v.getVersion()), v);
        }
        return rows.stream().map(g -> {
            CoreGoalVersion current = byKey.get(new CoreGoalVersion.Key(g.getId(), g.getCurrentVersion()));
            CoreGoalVersion first = byKey.get(new CoreGoalVersion.Key(g.getId(), 1));
            return new GoalInfo(g.getId(), g.getCourseId(), g.getCurrentVersion(), current.getText(),
                    first == null ? current.getText() : first.getText(), current.getShortLabel(), current.getKind(),
                    current.getRole(), current.getBloomLevel(), current.getSoloLevel(), g.getStatus(), g.getOrigin(),
                    g.getCreationProvenance(), g.getCreatedVia(), g.getCreatedBy(), g.getCreatedAt(),
                    g.getRemovedAt());
        }).toList();
    }

    private static GoalRelationInfo relation(CoreGoalRelationship r) {
        return new GoalRelationInfo(r.getId(), r.getSourceGoalId(), r.getTargetGoalId(), r.getType(),
                r.getConfidence(), r.getOrigin(), r.getCreatedAt());
    }
}
