package de.tum.cit.hestia.materials.goal;

import de.tum.cit.hestia.core.AlignmentApi;
import de.tum.cit.hestia.core.DomainModule;
import de.tum.cit.hestia.core.GoalInfo;
import de.tum.cit.hestia.core.LearningGoalApi;
import de.tum.cit.hestia.core.NewGoal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The materials view of a course's learning goals: the goals of {@code core} (through
 * {@link LearningGoalApi}) joined with their {@link GoalPlacement}, plus the bookkeeping that goes
 * with creating and removing them (placement and source rows, alignment links to documents).
 * Course roles are checked by {@code core} on every call.
 */
@Service
public class PlacedGoals {

    /** The alignment target type of a document. */
    public static final String DOCUMENT = "DOCUMENT";

    private final LearningGoalApi goalApi;
    private final AlignmentApi alignmentApi;
    private final GoalPlacementRepository placements;
    private final GoalSourceRepository sources;

    public PlacedGoals(LearningGoalApi goalApi, AlignmentApi alignmentApi, GoalPlacementRepository placements,
                       GoalSourceRepository sources) {
        this.goalApi = goalApi;
        this.alignmentApi = alignmentApi;
        this.placements = placements;
        this.sources = sources;
    }

    /** The goals of the course that are not removed, oldest first. */
    @Transactional(readOnly = true)
    public List<PlacedGoal> list(UUID courseId) {
        return wrap(goalApi.goals(courseId));
    }

    /** The goals of the course for which {@code keep} holds. */
    public List<PlacedGoal> list(UUID courseId, java.util.function.Predicate<PlacedGoal> keep) {
        return list(courseId).stream().filter(keep).toList();
    }

    /** The goals, in the given order, each with its placement. */
    @Transactional(readOnly = true)
    public List<PlacedGoal> wrap(List<GoalInfo> infos) {
        if (infos.isEmpty()) {
            return List.of();
        }
        Map<UUID, GoalPlacement> byGoal = placements
                .findByGoalIdIn(infos.stream().map(GoalInfo::id).toList()).stream()
                .collect(Collectors.toMap(GoalPlacement::getGoalId, Function.identity()));
        return infos.stream().map(info -> new PlacedGoal(info, byGoal.get(info.id()))).toList();
    }

    /** One goal of the course that is not removed, or empty. */
    @Transactional(readOnly = true)
    public Optional<PlacedGoal> find(UUID courseId, UUID goalId) {
        GoalInfo info;
        try {
            info = goalApi.goal(goalId);
        } catch (de.tum.cit.hestia.core.GoalNotFoundException e) {
            return Optional.empty();
        }
        if (!info.courseId().equals(courseId) || info.removed()) {
            return Optional.empty();
        }
        return Optional.of(wrap(List.of(info)).get(0));
    }

    /** One goal of the course, or a 404. */
    @Transactional(readOnly = true)
    public PlacedGoal require(UUID courseId, UUID goalId) {
        return find(courseId, goalId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Learning goal " + goalId + " not found in course " + courseId));
    }

    /** Creates goals (one transaction in core) and their placements; returned in the given order. */
    @Transactional
    public List<PlacedGoal> createAll(UUID courseId, List<NewPlacedGoal> items) {
        if (items.isEmpty()) {
            return List.of();
        }
        List<GoalInfo> created = goalApi.createAll(courseId, items.stream().map(NewPlacedGoal::goal).toList());
        List<GoalPlacement> rows = new ArrayList<>();
        List<PlacedGoal> result = new ArrayList<>();
        for (int i = 0; i < created.size(); i++) {
            NewPlacedGoal item = items.get(i);
            GoalPlacement placement = null;
            if (item.node() != null || item.examBlock() != null || item.lectureOrder() != null) {
                placement = new GoalPlacement(created.get(i).id(), item.node(), item.examBlock(), item.lectureOrder());
                rows.add(placement);
            }
            result.add(new PlacedGoal(created.get(i), placement));
        }
        placements.saveAll(rows);
        placements.flush();
        return result;
    }

    @Transactional
    public PlacedGoal create(UUID courseId, NewPlacedGoal item) {
        return createAll(courseId, List.of(item)).get(0);
    }

    /**
     * Stores a goal's source in a document and links the goal to the document (alignment). A goal
     * has one source per document; the one that exists is replaced.
     */
    @Transactional
    public void saveSource(GoalSource source) {
        sources.save(source);
        alignmentApi.link(source.getGoalId(), DomainModule.MATERIALS, DOCUMENT,
                source.getId().getDocumentId().toString());
    }

    /**
     * Removes goals softly in core and deletes materials' own rows for them (placement, sources). The
     * alignment links of the goals stay, as {@link LearningGoalApi#removeAll} documents.
     */
    @Transactional
    public void removeAll(Collection<UUID> goalIds) {
        if (goalIds.isEmpty()) {
            return;
        }
        goalApi.removeAll(goalIds);
        sources.deleteByIdGoalIdIn(goalIds);
        placements.deleteByGoalIdIn(goalIds);
        sources.flush();
    }

    /** Placement rows to change; creates the row of a goal that has none. */
    @Transactional
    public GoalPlacement placementFor(UUID goalId) {
        return placements.findById(goalId).orElseGet(() -> placements.save(new GoalPlacement(goalId, null, null, null)));
    }

    @Transactional(readOnly = true)
    public Map<UUID, GoalPlacement> placementsById(Collection<UUID> goalIds) {
        return goalIds.isEmpty() ? new HashMap<>() : placements.findByGoalIdIn(goalIds).stream()
                .collect(Collectors.toMap(GoalPlacement::getGoalId, Function.identity()));
    }

    /** Throws a 409 when the course already has a topic with this text, ignoring case. */
    @Transactional(readOnly = true)
    public void rejectDuplicateTopic(UUID courseId, String text) {
        boolean duplicate = goalApi.goals(courseId).stream()
                .filter(goal -> goal.origin() == de.tum.cit.hestia.core.GoalOrigin.TERMINAL)
                .anyMatch(goal -> goal.text() != null && goal.text().strip().equalsIgnoreCase(text));
        if (duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A topic with this text already exists");
        }
    }

    /** One past the highest lecture order of the course's goals; 0 when none has one. */
    @Transactional(readOnly = true)
    public int nextLectureOrder(UUID courseId) {
        return list(courseId).stream()
                .map(PlacedGoal::lectureOrder)
                .filter(java.util.Objects::nonNull)
                .mapToInt(Integer::intValue)
                .max()
                .orElse(-1) + 1;
    }
}
