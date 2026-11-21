package de.tum.cit.hestia.core.internal.course;

import de.tum.cit.hestia.core.CourseRole;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What happens to a user's courses when the account is deleted (inactivity deletion):
 * <ul>
 *   <li>a course with no other member (the personal one included) is deleted with all its content,
 *       through the same path as {@code CourseApi.delete} (module hooks, stored files after commit);</li>
 *   <li>a course with other members stays with all its content; only the membership goes. If the user
 *       was its only owner, the longest-standing editor becomes owner, else the longest-standing viewer.
 *       A personal course that other members share becomes an ordinary course.</li>
 * </ul>
 * Runs for the organisation in {@code OrgContext}, without a logged-in user.
 */
@Service
public class CourseRelease {

    private static final Logger log = LoggerFactory.getLogger(CourseRelease.class);

    private final CourseService courseService;
    private final CoreCourseRepository courses;
    private final CourseMembershipRepository memberships;
    private final Clock clock = Clock.systemUTC();

    CourseRelease(CourseService courseService, CoreCourseRepository courses, CourseMembershipRepository memberships) {
        this.courseService = courseService;
        this.courses = courses;
        this.memberships = memberships;
    }

    /** Counts of what {@link #release(UUID)} did or, in a dry run, would do. */
    public record Outcome(int deletedCourses, int keptCourses, int ownersPromoted) {}

    /** What {@link #release(UUID)} would do, without changing anything. */
    @Transactional(readOnly = true)
    public Outcome preview(UUID userId) {
        int deleted = 0;
        int kept = 0;
        int promoted = 0;
        for (CourseMembership membership : membershipsOf(userId)) {
            List<CourseMembership> members = memberships.findByCourseIdOrderByCreatedAt(membership.getCourseId());
            if (members.size() <= 1) {
                deleted++;
            } else {
                kept++;
                if (successor(members, userId).isPresent()) {
                    promoted++;
                }
            }
        }
        return new Outcome(deleted, kept, promoted);
    }

    /** Deletes the user's solo courses and leaves the shared ones; must run inside the deleting transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Outcome release(UUID userId) {
        int deleted = 0;
        int kept = 0;
        int promoted = 0;
        Instant now = clock.instant();
        for (CourseMembership membership : membershipsOf(userId)) {
            UUID courseId = membership.getCourseId();
            // Membership changes of one course run one at a time (as in the member endpoints).
            CoreCourse course = courses.lockById(courseId).orElse(null);
            if (course == null) {
                continue;
            }
            List<CourseMembership> members = memberships.findByCourseIdOrderByCreatedAt(courseId);
            if (members.size() <= 1) {
                courseService.deleteWithoutCheck(course);
                deleted++;
                continue;
            }
            Optional<CourseMembership> successor = successor(members, userId);
            successor.ifPresent(m -> m.setRole(CourseRole.OWNER));
            if (successor.isPresent()) {
                promoted++;
            }
            if (course.isPersonal() && userId.equals(course.getCreatedBy())) {
                course.unmarkPersonal(now);
            }
            memberships.delete(membership);
            kept++;
        }
        memberships.flush();
        log.debug("Released courses of user {}: {} deleted, {} kept, {} owners promoted", userId, deleted, kept, promoted);
        return new Outcome(deleted, kept, promoted);
    }

    private List<CourseMembership> membershipsOf(UUID userId) {
        return memberships.findByUserIdAndRoleIn(userId, Arrays.asList(CourseRole.values()));
    }

    /**
     * The member who becomes owner when {@code userId} leaves: none if {@code userId} is no owner or
     * another owner remains; else the longest-standing editor, else the longest-standing viewer.
     */
    private static Optional<CourseMembership> successor(List<CourseMembership> members, UUID userId) {
        boolean leavingOwner = members.stream()
                .anyMatch(m -> m.getUserId().equals(userId) && m.getRole() == CourseRole.OWNER);
        boolean otherOwner = members.stream()
                .anyMatch(m -> !m.getUserId().equals(userId) && m.getRole() == CourseRole.OWNER);
        if (!leavingOwner || otherOwner) {
            return Optional.empty();
        }
        // members are ordered by membership age; a stable sort keeps that order within a role
        return members.stream()
                .filter(m -> !m.getUserId().equals(userId))
                .sorted(Comparator.comparing(m -> m.getRole() == CourseRole.EDITOR ? 0 : 1))
                .findFirst();
    }
}
