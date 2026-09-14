package app.taskblock;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface TaskBlockRepository extends JpaRepository<TaskBlock, UUID> {
    List<TaskBlock> findByExamIdOrderByPositionAsc(UUID examId);
    List<TaskBlock> findBySectionIdOrderByPositionAsc(UUID sectionId);
    List<TaskBlock> findByExamIdAndSectionIdOrderByPositionAsc(UUID examId, UUID sectionId);
    List<TaskBlock> findByExamIdAndSectionIdIsNullOrderByPositionAsc(UUID examId);
    long countByExamId(UUID examId);

    /** Bulk load for dashboard progress aggregation across many exams (avoids N+1). */
    List<TaskBlock> findByExamIdIn(List<UUID> examIds);

    @Transactional
    void deleteByExamIdAndSectionId(UUID examId, UUID sectionId);

    /** Used by the parse persister to clear a previous parse's tasks before re-inserting. */
    @Transactional
    void deleteByExamId(UUID examId);

    // Position-shift for add-with-shift inserts (mirrors shift_and_insert_task).
    @Modifying
    @Transactional
    @Query("update TaskBlock t set t.position = t.position + 1 "
        + "where t.examId = :examId and t.sectionId = :sectionId and t.position >= :fromPos")
    int shiftTaskBlocksInSection(@Param("examId") UUID examId, @Param("sectionId") UUID sectionId, @Param("fromPos") int fromPos);

    @Modifying
    @Transactional
    @Query("update TaskBlock t set t.position = t.position + 1 "
        + "where t.examId = :examId and t.sectionId is null and t.position >= :fromPos")
    int shiftTaskBlocksNullSection(@Param("examId") UUID examId, @Param("fromPos") int fromPos);
}
