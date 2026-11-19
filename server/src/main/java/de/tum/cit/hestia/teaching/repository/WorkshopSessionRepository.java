package de.tum.cit.hestia.teaching.repository;

import de.tum.cit.hestia.teaching.model.WorkshopSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface WorkshopSessionRepository extends JpaRepository<WorkshopSessionEntity, String> {

    /** The user's own sessions without a course, in dashboard order. */
    @Query("SELECT w FROM WorkshopSessionEntity w WHERE w.courseId IS NULL AND w.createdBy = :userId ORDER BY w.displayOrder ASC NULLS LAST, w.createdAt DESC")
    List<WorkshopSessionEntity> findWithoutCourseOf(@Param("userId") UUID userId);

    /** Sessions without a course created by the user, plus all sessions of the courses on the given core courses. */
    @Query("SELECT w FROM WorkshopSessionEntity w WHERE (w.courseId IS NULL AND w.createdBy = :userId) "
            + "OR w.courseId IN (SELECT c.id FROM CourseEntity c WHERE c.coreCourseId IN :coreCourseIds) "
            + "ORDER BY w.displayOrder ASC NULLS LAST, w.createdAt DESC")
    List<WorkshopSessionEntity> findVisible(@Param("userId") UUID userId, @Param("coreCourseIds") Collection<UUID> coreCourseIds);

    @Query("SELECT w FROM WorkshopSessionEntity w WHERE w.courseId = :courseId ORDER BY w.displayOrder ASC NULLS LAST, w.createdAt DESC")
    List<WorkshopSessionEntity> findAllByCourseIdOrdered(@Param("courseId") String courseId);

    long countByCreatedBy(UUID createdBy);
}
