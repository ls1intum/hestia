package de.tum.cit.hestia.core;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The learning goals of a course, the only place goals live. Every method checks the current
 * user's role in the goal's course itself: reading needs a membership, all changes {@code EDITOR}.
 * A goal the user cannot read is reported as {@link GoalNotFoundException} (or
 * {@link CourseNotFoundException} for a course id). Changing one of the versioned fields writes a
 * new version; status and relationship changes do not.
 */
public interface LearningGoalApi {

    /** The goals of a course that are not removed, oldest first. */
    List<GoalInfo> goals(UUID courseId);

    /** One goal, also if it was removed. */
    GoalInfo goal(UUID goalId);

    /**
     * The goals with these ids that the user can read, removed ones included, in no particular
     * order. Unknown and unreadable ids are skipped.
     */
    List<GoalInfo> goalsById(Collection<UUID> goalIds);

    GoalInfo create(UUID courseId, NewGoal goal);

    /** Creates several goals in one transaction, returned in the given order. */
    List<GoalInfo> createAll(UUID courseId, List<NewGoal> goals);

    GoalInfo update(UUID goalId, GoalChange change);

    GoalInfo setStatus(UUID goalId, GoalStatus status);

    /**
     * Removes a goal softly: it disappears from {@link #goals(UUID)} and {@link #relationships},
     * its relationships are deleted, its alignment links stay. Removing a removed goal does nothing.
     */
    void remove(UUID goalId);

    /** {@link #remove(UUID)} for several goals in one transaction. */
    void removeAll(Collection<UUID> goalIds);

    /** All versions of a goal, oldest first. */
    List<GoalVersionInfo> history(UUID goalId);

    /**
     * Relates two goals of the same course; returns the existing relationship if one with the same
     * source, target and type exists.
     */
    GoalRelationInfo relate(UUID sourceGoalId, UUID targetGoalId, String type, double confidence, String origin);

    void unrelate(UUID relationshipId);

    /** The relationships between the course's goals. */
    List<GoalRelationInfo> relationships(UUID courseId);
}
