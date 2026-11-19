package de.tum.cit.hestia.core.internal.course;

import de.tum.cit.hestia.core.CourseRole;
import de.tum.cit.hestia.core.internal.identity.AppUser;
import de.tum.cit.hestia.core.internal.identity.AppUserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Members of a course, for its owners only ({@code /core/api/courses/{courseId}/members}). A member is
 * added by external id and must have logged in once in the same organisation. A course always keeps
 * at least one owner: changes to a course's members lock the course row first, so two owners
 * demoting each other at the same time cannot both succeed.
 */
@RestController
@RequestMapping("/api/courses/{courseId}/members")
class CourseMemberController {

    record Member(UUID userId, String externalId, String displayName, CourseRole role) {}

    record MemberRequest(@NotBlank String externalId, @NotNull CourseRole role) {}

    private final CourseService courses;
    private final CourseMembershipRepository memberships;
    private final AppUserRepository users;
    private final CoreCourseRepository courseRows;
    private final Clock clock = Clock.systemUTC();

    CourseMemberController(CourseService courses, CourseMembershipRepository memberships, AppUserRepository users,
            CoreCourseRepository courseRows) {
        this.courses = courses;
        this.courseRows = courseRows;
        this.memberships = memberships;
        this.users = users;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<Member> list(@PathVariable UUID courseId) {
        courses.requireOwner(courseId);
        List<CourseMembership> members = memberships.findByCourseIdOrderByCreatedAt(courseId);
        Map<UUID, AppUser> byId = users.findAllById(members.stream().map(CourseMembership::getUserId).toList())
                .stream().collect(Collectors.toMap(AppUser::getId, Function.identity()));
        return members.stream().map(m -> member(byId.get(m.getUserId()), m.getRole())).toList();
    }

    /** Adds a member or changes a member's role. */
    @PutMapping
    @Transactional
    public Member put(@PathVariable UUID courseId, @Valid @RequestBody MemberRequest request) {
        courseRows.lockById(courseId);
        courses.requireOwner(courseId);
        AppUser user = users.findByExternalId(request.externalId().trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No user with this id has logged in in this organisation"));
        var key = new CourseMembership.Key(courseId, user.getId());
        CourseMembership membership = memberships.findById(key).orElse(null);
        if (membership == null) {
            memberships.save(new CourseMembership(courseId, user.getId(), request.role(), clock.instant()));
        } else {
            if (membership.getRole() == CourseRole.OWNER && request.role() != CourseRole.OWNER) {
                requireAnotherOwner(courseId);
            }
            membership.setRole(request.role());
        }
        return member(user, request.role());
    }

    @DeleteMapping("/{userId}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable UUID courseId, @PathVariable UUID userId) {
        courseRows.lockById(courseId);
        courses.requireOwner(courseId);
        CourseMembership membership = memberships.findById(new CourseMembership.Key(courseId, userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not a member"));
        if (membership.getRole() == CourseRole.OWNER) {
            requireAnotherOwner(courseId);
        }
        memberships.delete(membership);
        return ResponseEntity.noContent().build();
    }

    private void requireAnotherOwner(UUID courseId) {
        if (memberships.countByCourseIdAndRole(courseId, CourseRole.OWNER) < 2) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A course needs at least one owner");
        }
    }

    private static Member member(AppUser user, CourseRole role) {
        return new Member(user.getId(), user.getExternalId(), user.getDisplayName(), role);
    }
}
