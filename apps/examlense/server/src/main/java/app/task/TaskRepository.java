package app.task;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface TaskRepository extends JpaRepository<Task, UUID> {
    List<Task> findByExamIdOrderByPositionAsc(UUID examId);
    List<Task> findBySectionIdOrderByPositionAsc(UUID sectionId);
    List<Task> findByExamIdAndSectionIdOrderByPositionAsc(UUID examId, UUID sectionId);
    List<Task> findByExamIdAndSectionIdIsNullOrderByPositionAsc(UUID examId);
    long countByExamId(UUID examId);

    /** Bulk load for dashboard progress aggregation across many exams (avoids N+1). */
    List<Task> findByExamIdIn(List<UUID> examIds);

    @Transactional
    void deleteByExamIdAndSectionId(UUID examId, UUID sectionId);

    /** Used by the parse persister to clear a previous parse's tasks before re-inserting. */
    @Transactional
    void deleteByExamId(UUID examId);

    // Position-shift for add-with-shift inserts (mirrors shift_and_insert_task).
    @Modifying
    @Transactional
    @Query("update Task t set t.position = t.position + 1 "
        + "where t.examId = :examId and t.sectionId = :sectionId and t.position >= :fromPos")
    int shiftTasksInSection(@Param("examId") UUID examId, @Param("sectionId") UUID sectionId, @Param("fromPos") int fromPos);

    @Modifying
    @Transactional
    @Query("update Task t set t.position = t.position + 1 "
        + "where t.examId = :examId and t.sectionId is null and t.position >= :fromPos")
    int shiftTasksNullSection(@Param("examId") UUID examId, @Param("fromPos") int fromPos);
}
