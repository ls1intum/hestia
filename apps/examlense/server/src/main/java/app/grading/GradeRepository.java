package app.grading;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GradeRepository extends JpaRepository<Grade, UUID> {

    @Query("select g from Grade g join fetch g.answer a where a.examId = :examId")
    List<Grade> findByExamId(@Param("examId") UUID examId);

    @Query("select g from Grade g join fetch g.answer a where a.id = :answerId")
    Optional<Grade> findByAnswerId(@Param("answerId") UUID answerId);

    /** Bulk load for dashboard progress aggregation across many exams (avoids N+1). */
    @Query("select g from Grade g join fetch g.answer a where a.examId in :examIds")
    List<Grade> findByExamIdIn(@Param("examIds") List<UUID> examIds);
}
