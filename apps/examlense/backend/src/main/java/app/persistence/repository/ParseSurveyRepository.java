package app.persistence.repository;

import app.persistence.entity.ParseSurvey;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ParseSurveyRepository extends JpaRepository<ParseSurvey, UUID> {
    List<ParseSurvey> findAllByOrderByCreatedAtDesc();

    /**
     * Parsing-quality survey scores grouped by the parser model that produced the
     * surveyed exam. The model is denormalized onto the survey row at submit time
     * (see V2 migration), so this rollup is independent of the exam's lifetime —
     * responses for since-deleted exams still count. Responses with no model
     * (pre-V2 rows whose exam was already gone at backfill) fall into
     * {@code "unknown"}. AVG ignores nulls, so an aspect left blank on a response
     * doesn't drag its model's average down.
     *
     * Each row: [modelId(String), responses(Long), avgSpeed(Double),
     * avgContentCorrectness(Double), avgStructure(Double)]; the avg is null when
     * no response for that model rated the aspect. Ordered by response count desc.
     */
    @Query("""
        SELECT COALESCE(s.parserModel, 'unknown'),
               COUNT(s),
               AVG(s.speed),
               AVG(s.contentCorrectness),
               AVG(s.structure)
        FROM ParseSurvey s
        GROUP BY COALESCE(s.parserModel, 'unknown')
        ORDER BY COUNT(s) DESC
        """)
    List<Object[]> aggregateByModel();
}
