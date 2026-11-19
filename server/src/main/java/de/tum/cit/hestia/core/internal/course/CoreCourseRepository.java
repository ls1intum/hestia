package de.tum.cit.hestia.core.internal.course;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Courses of the current organisation only (tenant filter). */
interface CoreCourseRepository extends JpaRepository<CoreCourse, UUID> {

    @Query("select c from CoreCourse c where c.id in "
            + "(select m.courseId from CourseMembership m where m.userId = :userId) "
            + "order by c.createdAt desc, c.id")
    List<CoreCourse> findMine(UUID userId, Pageable page);
}
