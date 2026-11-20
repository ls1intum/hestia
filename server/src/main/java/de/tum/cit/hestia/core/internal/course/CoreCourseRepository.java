package de.tum.cit.hestia.core.internal.course;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Courses of the current organisation only (tenant filter). */
interface CoreCourseRepository extends JpaRepository<CoreCourse, UUID> {

    @Query("select c from CoreCourse c where c.id in "
            + "(select m.courseId from CourseMembership m where m.userId = :userId) "
            + "order by c.createdAt desc, c.id")
    List<CoreCourse> findMine(UUID userId, Pageable page);

    @Query("select c from CoreCourse c where c.createdBy = :userId and c.personal = true")
    Optional<CoreCourse> findPersonal(UUID userId);

    @Modifying
    @Query(nativeQuery = true, value = "insert into core.course (org_id, title, personal, created_by) "
            + "values (:orgId, :title, true, :userId) on conflict (created_by) where personal do nothing")
    void insertPersonalIfMissing(UUID orgId, UUID userId, String title);

    /** Locks the course row until the transaction ends, so membership changes run one at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CoreCourse c where c.id = :id")
    Optional<CoreCourse> lockById(UUID id);
}
