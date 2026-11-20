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

    /** The sessions of the given courses (the ones the user may see), in dashboard order. */
    @Query("SELECT w FROM WorkshopSessionEntity w WHERE w.courseId IN :courseIds ORDER BY w.displayOrder ASC NULLS LAST, w.createdAt DESC")
    List<WorkshopSessionEntity> findVisible(@Param("courseIds") Collection<UUID> courseIds);

    @Query("SELECT w FROM WorkshopSessionEntity w WHERE w.courseId = :courseId ORDER BY w.displayOrder ASC NULLS LAST, w.createdAt DESC")
    List<WorkshopSessionEntity> findAllByCourseIdOrdered(@Param("courseId") UUID courseId);

    long countByCreatedBy(UUID createdBy);
}
