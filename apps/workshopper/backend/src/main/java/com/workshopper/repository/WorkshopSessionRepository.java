package com.workshopper.repository;

import com.workshopper.model.WorkshopSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WorkshopSessionRepository extends JpaRepository<WorkshopSessionEntity, String> {
    @org.springframework.data.jpa.repository.Query("SELECT w FROM WorkshopSessionEntity w ORDER BY w.displayOrder ASC NULLS LAST, w.createdAt DESC")
    List<WorkshopSessionEntity> findAllOrdered();

    @org.springframework.data.jpa.repository.Query("SELECT w FROM WorkshopSessionEntity w WHERE w.lectureId = :lectureId ORDER BY w.displayOrder ASC NULLS LAST, w.createdAt DESC")
    List<WorkshopSessionEntity> findAllByLectureIdOrdered(@org.springframework.data.repository.query.Param("lectureId") String lectureId);
}
