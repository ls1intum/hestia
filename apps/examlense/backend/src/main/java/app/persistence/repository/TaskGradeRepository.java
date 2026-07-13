package app.persistence.repository;

import app.persistence.entity.TaskGrade;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface TaskGradeRepository extends JpaRepository<TaskGrade, UUID> {

    List<TaskGrade> findByExamId(UUID examId);
    Optional<TaskGrade> findByTaskId(UUID taskId);

    /** Bulk load for dashboard progress aggregation across many exams (avoids N+1). */
    List<TaskGrade> findByExamIdIn(List<UUID> examIds);

    @Transactional
    void deleteByExamIdAndAutoGradedTrue(UUID examId);

    @Transactional
    void deleteByTaskIdInAndAutoGradedTrue(List<UUID> taskIds);

    @Transactional
    void deleteByTaskIdAndAutoGradedTrue(UUID taskId);
}
