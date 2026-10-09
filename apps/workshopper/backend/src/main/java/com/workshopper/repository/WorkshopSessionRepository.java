package com.workshopper.repository;

import com.workshopper.model.WorkshopSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WorkshopSessionRepository extends JpaRepository<WorkshopSessionEntity, String> {

    @Query("SELECT w FROM WorkshopSessionEntity w ORDER BY w.displayOrder ASC NULLS LAST, w.createdAt DESC")
    List<WorkshopSessionEntity> findAllOrdered();

    @Query("SELECT w FROM WorkshopSessionEntity w WHERE w.ownerId = :ownerId ORDER BY w.displayOrder ASC NULLS LAST, w.createdAt DESC")
    List<WorkshopSessionEntity> findByOwnerIdOrdered(@Param("ownerId") String ownerId);

    @Query("SELECT w FROM WorkshopSessionEntity w WHERE w.courseId = :courseId ORDER BY w.displayOrder ASC NULLS LAST, w.createdAt DESC")
    List<WorkshopSessionEntity> findAllByCourseIdOrdered(@Param("courseId") String courseId);
}
