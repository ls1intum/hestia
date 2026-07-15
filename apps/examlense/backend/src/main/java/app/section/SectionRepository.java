package app.section;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface SectionRepository extends JpaRepository<Section, UUID> {

    List<Section> findByExamIdOrderByPositionAsc(UUID examId);

    Optional<Section> findByIdAndExamId(UUID id, UUID examId);

    /** Used by the parse persister to clear a previous parse's sections (blocks cascade via FK). */
    @Transactional
    void deleteByExamId(UUID examId);

    /**
     * Atomic compare-and-set solve lock. Sets solve_started_at = now only if the
     * section is currently unlocked or the prior lock is older than the cutoff
     * (stale). Returns rows-affected: 1 = lock acquired, 0 = someone else holds it.
     */
    @Modifying
    @Transactional
    @Query("update Section s set s.solveStartedAt = :now "
        + "where s.id = :id and s.examId = :examId "
        + "and (s.solveStartedAt is null or s.solveStartedAt < :cutoff)")
    int acquireSolveLock(@Param("id") UUID id, @Param("examId") UUID examId,
                         @Param("now") OffsetDateTime now, @Param("cutoff") OffsetDateTime cutoff);

    @Modifying
    @Transactional
    @Query("update Section s set s.solveStartedAt = null where s.id = :id")
    int releaseSolveLock(@Param("id") UUID id);

    /**
     * Atomic compare-and-set lock for the LearningGoalHub goal-generation run
     * (same pattern as {@link #acquireSolveLock}, separate column so goal
     * generation and solving never contend with each other).
     */
    @Modifying
    @Transactional
    @Query("update Section s set s.goalsStartedAt = :now "
        + "where s.id = :id and s.examId = :examId "
        + "and (s.goalsStartedAt is null or s.goalsStartedAt < :cutoff)")
    int acquireGoalsLock(@Param("id") UUID id, @Param("examId") UUID examId,
                         @Param("now") OffsetDateTime now, @Param("cutoff") OffsetDateTime cutoff);

    @Modifying
    @Transactional
    @Query("update Section s set s.goalsStartedAt = null where s.id = :id")
    int releaseGoalsLock(@Param("id") UUID id);

    @Modifying
    @Transactional
    @Query("update Section s set s.position = s.position + 1 "
        + "where s.examId = :examId and s.position >= :fromPos")
    int shiftSectionsFrom(@Param("examId") UUID examId, @Param("fromPos") int fromPos);
}
