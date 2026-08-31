package de.tum.cit.hestia.learninggoalhub.extraction;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExtractionRunRepository extends JpaRepository<ExtractionRun, Long> {

    List<ExtractionRun> findByCourseId(Long courseId);

    Optional<ExtractionRun> findFirstByCourseIdOrderByStartedAtDesc(Long courseId);

    @Query("select run from ExtractionRun run join fetch run.course course "
            + "where course.id in :courseIds order by run.startedAt desc")
    List<ExtractionRun> findLatestCandidatesByCourseIds(@Param("courseIds") List<Long> courseIds);
}
