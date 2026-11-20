package de.tum.cit.hestia.materials.course;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MaterialSettingsRepository extends JpaRepository<MaterialSettings, UUID> {

    List<MaterialSettings> findByCourseIdIn(Collection<UUID> courseIds);

    /** Creates the settings of a course with the defaults unless they exist; safe under concurrency. */
    @Modifying
    @Query(value = "insert into materials.material_settings (course_id, org_id, figures_enabled) "
            + "values (:courseId, :orgId, false) on conflict (course_id) do nothing", nativeQuery = true)
    void insertDefaults(@Param("courseId") UUID courseId, @Param("orgId") UUID orgId);

    /** The settings, their row locked until the surrounding transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from MaterialSettings s where s.courseId = :courseId")
    Optional<MaterialSettings> lockByCourseId(@Param("courseId") UUID courseId);

    // The count query names the document entity in a JPQL string, which the package cycle test cannot
    // see; it is the one place course depends on the document package.

    /** Document count per course for the given ids — one row per course that has at least one document. */
    @Query("select d.courseId as courseId, count(d.id) as count "
            + "from Document d where d.courseId in :courseIds group by d.courseId")
    List<CourseCount> countDocumentsByCourseIds(@Param("courseIds") Collection<UUID> courseIds);

    /** Projection for the grouped count query above. */
    interface CourseCount {
        UUID getCourseId();

        long getCount();
    }
}
