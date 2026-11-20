package de.tum.cit.hestia.materials.extraction;

import java.util.UUID;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExtractionRunRepository extends JpaRepository<ExtractionRun, Long> {

    List<ExtractionRun> findByCourseId(UUID courseId);

    Optional<ExtractionRun> findFirstByCourseIdOrderByStartedAtDesc(UUID courseId);

    @Query("select run from ExtractionRun run "
            + "where run.courseId in :courseIds order by run.startedAt desc")
    List<ExtractionRun> findLatestCandidatesByCourseIds(@Param("courseIds") List<UUID> courseIds);
}
