package app.persistence.repository;

import app.persistence.entity.TaskAnswer;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface TaskAnswerRepository extends JpaRepository<TaskAnswer, UUID> {

    List<TaskAnswer> findByExamId(UUID examId);
    List<TaskAnswer> findByTaskId(UUID taskId);
    long countByExamId(UUID examId);

    /** Bulk load for dashboard progress aggregation across many exams (avoids N+1). */
    List<TaskAnswer> findByExamIdIn(List<UUID> examIds);

    @Transactional
    void deleteByExamId(UUID examId);

    @Transactional
    void deleteByTaskId(UUID taskId);

    @Transactional
    void deleteByTaskIdIn(List<UUID> taskIds);

    @Query("select count(distinct a.taskId) from TaskAnswer a where a.examId = :examId")
    long countDistinctTasksAnswered(@Param("examId") UUID examId);
}
