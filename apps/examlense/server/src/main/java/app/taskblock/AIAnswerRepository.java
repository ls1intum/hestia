package app.taskblock;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface AIAnswerRepository extends JpaRepository<AIAnswer, UUID> {

    List<AIAnswer> findByExamId(UUID examId);
    List<AIAnswer> findByTaskId(UUID taskId);
    long countByExamId(UUID examId);

    /** Bulk load for dashboard progress aggregation across many exams (avoids N+1). */
    List<AIAnswer> findByExamIdIn(List<UUID> examIds);

    @Transactional
    void deleteByExamId(UUID examId);

    @Transactional
    void deleteByTaskId(UUID taskId);

    @Transactional
    void deleteByTaskIdIn(List<UUID> taskIds);

    @Query("select count(distinct a.taskId) from AIAnswer a where a.examId = :examId")
    long countDistinctTaskBlocksAnswered(@Param("examId") UUID examId);
}
