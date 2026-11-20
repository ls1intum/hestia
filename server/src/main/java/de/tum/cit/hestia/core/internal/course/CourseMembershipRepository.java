package de.tum.cit.hestia.core.internal.course;

import de.tum.cit.hestia.core.CourseRole;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Memberships of the current organisation only (tenant filter). */
interface CourseMembershipRepository extends JpaRepository<CourseMembership, CourseMembership.Key> {

    List<CourseMembership> findByCourseIdOrderByCreatedAt(UUID courseId);

    List<CourseMembership> findByUserIdAndRoleIn(UUID userId, Collection<CourseRole> roles);

    long countByCourseIdAndRole(UUID courseId, CourseRole role);

    @Modifying
    @Query(nativeQuery = true, value = "insert into core.course_membership (course_id, user_id, org_id, role) "
            + "values (:courseId, :userId, :orgId, :role) on conflict do nothing")
    void insertIfMissing(UUID courseId, UUID userId, UUID orgId, String role);
}
