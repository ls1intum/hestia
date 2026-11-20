package de.tum.cit.hestia.core.internal.course;

import de.tum.cit.hestia.core.CourseAccess;
import de.tum.cit.hestia.core.CourseAccessDeniedException;
import de.tum.cit.hestia.core.CourseApi;
import de.tum.cit.hestia.core.CourseInfo;
import de.tum.cit.hestia.core.CourseNotFoundException;
import de.tum.cit.hestia.core.CourseRole;
import de.tum.cit.hestia.core.CurrentUser;
import de.tum.cit.hestia.core.OrgContext;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Courses and course roles of {@code core}. A missing membership looks like a missing course (404);
 * a membership with too small a role is refused (403). Named {@code courseAccess} for
 * {@code @PreAuthorize("@courseAccess.canWrite(#courseId)")}.
 */
@Service("courseAccess")
@Transactional(readOnly = true)
class CourseService implements CourseAccess, CourseApi {

    private final CoreCourseRepository courses;
    private final CourseMembershipRepository memberships;
    private final CurrentUser currentUser;
    private final Clock clock = Clock.systemUTC();

    /** Stored title of the personal course; the clients show their own label for it. */
    static final String PERSONAL_TITLE = "Einzelkram";

    CourseService(CoreCourseRepository courses, CourseMembershipRepository memberships, CurrentUser currentUser) {
        this.courses = courses;
        this.memberships = memberships;
        this.currentUser = currentUser;
    }

    // --- CourseAccess

    @Override
    public void requireRead(UUID courseId) {
        require(courseId, CourseRole.VIEWER);
    }

    @Override
    public void requireWrite(UUID courseId) {
        require(courseId, CourseRole.EDITOR);
    }

    @Override
    public void requireOwner(UUID courseId) {
        require(courseId, CourseRole.OWNER);
    }

    @Override
    public boolean canRead(UUID courseId) {
        return role(courseId).isPresent();
    }

    @Override
    public boolean canWrite(UUID courseId) {
        return role(courseId).filter(r -> r.includes(CourseRole.EDITOR)).isPresent();
    }

    /** The current user's role in the course, empty without a membership. */
    Optional<CourseRole> role(UUID courseId) {
        if (courseId == null) {
            return Optional.empty();
        }
        return memberships.findById(new CourseMembership.Key(courseId, currentUser.userId()))
                .map(CourseMembership::getRole);
    }

    CourseRole require(UUID courseId, CourseRole needed) {
        CourseRole role = role(courseId).orElseThrow(() -> new CourseNotFoundException(courseId));
        if (!role.includes(needed)) {
            throw new CourseAccessDeniedException(courseId, needed);
        }
        return role;
    }

    // --- CourseApi

    @Override
    @Transactional
    public CourseInfo create(String title) {
        var now = clock.instant();
        UUID userId = currentUser.userId();
        CoreCourse course = courses.saveAndFlush(new CoreCourse(title, userId, now));
        memberships.save(new CourseMembership(course.getId(), userId, CourseRole.OWNER, now));
        return info(course, CourseRole.OWNER);
    }

    @Override
    @Transactional
    public CourseInfo personalCourse() {
        UUID userId = currentUser.userId();
        CoreCourse course = courses.findPersonal(userId).orElse(null);
        if (course == null) {
            // Two first requests at once: the partial unique index lets only one insert pass.
            courses.insertPersonalIfMissing(OrgContext.require(), userId, PERSONAL_TITLE);
            course = courses.findPersonal(userId).orElseThrow();
            memberships.insertIfMissing(course.getId(), userId, OrgContext.require(), CourseRole.OWNER.name());
        }
        return info(course, CourseRole.OWNER);
    }

    @Override
    public CourseInfo get(UUID courseId) {
        CourseRole role = require(courseId, CourseRole.VIEWER);
        return info(load(courseId), role);
    }

    @Override
    public List<CourseInfo> listMine(int page, int size) {
        UUID userId = currentUser.userId();
        return courses.findMine(userId, PageRequest.of(page, size)).stream()
                .map(c -> info(c, role(c.getId()).orElseThrow()))
                .toList();
    }

    @Override
    public List<UUID> idsMine(CourseRole role) {
        List<CourseRole> roles = Arrays.stream(CourseRole.values()).filter(r -> r.includes(role)).toList();
        return memberships.findByUserIdAndRoleIn(currentUser.userId(), roles).stream()
                .map(CourseMembership::getCourseId)
                .toList();
    }

    @Override
    @Transactional
    public CourseInfo rename(UUID courseId, String title) {
        CourseRole role = require(courseId, CourseRole.EDITOR);
        CoreCourse course = load(courseId);
        course.rename(title, clock.instant());
        return info(course, role);
    }

    @Override
    @Transactional
    public void delete(UUID courseId) {
        require(courseId, CourseRole.OWNER);
        CoreCourse course = load(courseId);
        if (course.isPersonal()) {
            throw new IllegalArgumentException("The personal course cannot be deleted");
        }
        courses.delete(course);
    }

    private CoreCourse load(UUID courseId) {
        return courses.findById(courseId).orElseThrow(() -> new CourseNotFoundException(courseId));
    }

    private static CourseInfo info(CoreCourse course, CourseRole role) {
        return new CourseInfo(course.getId(), course.getTitle(), role, course.getCreatedAt(), course.isPersonal());
    }
}
