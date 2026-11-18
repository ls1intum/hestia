package de.tum.cit.hestia.teaching.repository;

import de.tum.cit.hestia.teaching.model.CourseEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface CourseRepository extends JpaRepository<CourseEntity, String> {

    /** The courses on the given core courses (the ones the user may see), in dashboard order. */
    @Query("SELECT c FROM CourseEntity c WHERE c.coreCourseId IN :coreCourseIds ORDER BY c.displayOrder ASC NULLS LAST, c.createdAt DESC")
    List<CourseEntity> findByCoreCourseIdsOrdered(@Param("coreCourseIds") Collection<UUID> coreCourseIds);

    long countByCoreCourseIdIn(Collection<UUID> coreCourseIds);
}
