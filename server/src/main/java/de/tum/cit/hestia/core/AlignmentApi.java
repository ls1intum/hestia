package de.tum.cit.hestia.core;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Links between learning goals and the objects of the domain modules (documents, teaching sessions,
 * exam tasks). The module owning the object decides who may change its links and checks that
 * itself; {@code core} additionally requires {@code EDITOR} in the goal's course to make or remove
 * a single link, and returns only links of courses the user is a member of. A module removes the
 * links of an object it deletes ({@link #unlinkTarget}).
 *
 * <p>When a goal changes or is removed, {@code core} marks all its links for review
 * ({@code reviewState = REVIEW}, {@code reviewReason} {@code GOAL_CHANGED} or {@code GOAL_REMOVED})
 * in the same transaction. Nothing is rewritten automatically: the teacher checks the linked object
 * and {@link #confirm confirms} the link.
 */
public interface AlignmentApi {

    /** Links the goal to the object; returns the existing link if there is one. */
    AlignmentLinkInfo link(UUID goalId, DomainModule module, String targetType, String targetId);

    void unlink(UUID goalId, DomainModule module, String targetType, String targetId);

    /** Removes all links of the object. */
    void unlinkTarget(DomainModule module, String targetType, String targetId);

    /** Removes the object's links to goals of one course, for moving the object out of that course. */
    void unlinkTargetInCourse(DomainModule module, String targetType, String targetId, UUID courseId);

    /** The links of one object, oldest first. */
    List<AlignmentLinkInfo> goalsFor(DomainModule module, String targetType, String targetId);

    /** The links of several objects of one type, by target id; objects without links are missing. */
    Map<String, List<AlignmentLinkInfo>> goalsForTargets(
            DomainModule module, String targetType, Collection<String> targetIds);

    /** All links of a goal; needs read access to its course. */
    List<AlignmentLinkInfo> linksOfGoal(UUID goalId);

    /** The course's links marked for review, oldest first; needs read access to the course. */
    List<AlignmentReview> openReviews(UUID courseId);

    /**
     * Confirms a link after review: sets it {@code OK} and its seen version to {@code goalVersion},
     * the goal version the teacher checked ({@link AlignmentReview#goalVersion}). Throws
     * {@link AlignmentReviewOutdatedException} if the goal has another version by now. Confirming a
     * link that is not marked does nothing. Needs {@code EDITOR} in the link's course.
     */
    AlignmentLinkInfo confirm(UUID linkId, int goalVersion);
}
