package com.workshopper.repository;

import com.workshopper.model.CourseEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CourseRepository extends JpaRepository<CourseEntity, String> {

    @Query("SELECT c FROM CourseEntity c WHERE c.ownerId = :ownerId ORDER BY c.displayOrder ASC NULLS LAST, c.createdAt DESC")
    List<CourseEntity> findByOwnerIdOrdered(@Param("ownerId") String ownerId);
}
