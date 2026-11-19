package de.tum.cit.hestia.core.internal.course;

import de.tum.cit.hestia.core.CourseRole;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Memberships of the current organisation only (tenant filter). */
interface CourseMembershipRepository extends JpaRepository<CourseMembership, CourseMembership.Key> {

    List<CourseMembership> findByCourseIdOrderByCreatedAt(UUID courseId);

    List<CourseMembership> findByUserIdAndRoleIn(UUID userId, Collection<CourseRole> roles);

    long countByCourseIdAndRole(UUID courseId, CourseRole role);
}
