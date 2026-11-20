package de.tum.cit.hestia.core;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Links between learning goals and the objects of the domain modules (documents, teaching sessions,
 * exam tasks). The module owning the object decides who may change its links and checks that
 * itself; {@code core} additionally requires that the user can read the goal's course when a link
 * is made or listed by goal. A module removes the links of an object it deletes
 * ({@link #unlinkTarget}).
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
}
