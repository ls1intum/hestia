package app.persistence.repository;

import app.persistence.entity.Exam;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ExamRepository extends JpaRepository<Exam, UUID> {

    List<Exam> findByOwnerIdOrderByCreatedAtDesc(UUID ownerId);

    Optional<Exam> findByIdAndOwnerId(UUID id, UUID ownerId);

    // --- Targeted control-plane updates (touch only the named columns) ---
    // Ownership is verified up-front in the services before these run, and
    // owner_id is immutable for the life of a parse/solve, so these filter by
    // id only. Each runs in its own transaction so it commits independently
    // (matching the previous fire-and-forget PostgREST update semantics).

    @Modifying
    @Transactional
    @Query("update Exam e set e.parsePhase = :phase where e.id = :id")
    int updateParsePhase(@Param("id") UUID id, @Param("phase") String phase);

    @Modifying
    @Transactional
    @Query("update Exam e set e.parseRawText = :text where e.id = :id")
    int updateParseRawText(@Param("id") UUID id, @Param("text") String text);

    @Modifying
    @Transactional
    @Query("update Exam e set e.status = 'failed', e.parseError = :error, e.parsePhase = null where e.id = :id")
    int markParseFailed(@Param("id") UUID id, @Param("error") String error);

    @Modifying
    @Transactional
    @Query("update Exam e set e.status = :status where e.id = :id")
    int updateStatus(@Param("id") UUID id, @Param("status") String status);

    /**
     * Flip status only if it is still {@code from} — a compare-and-set so a
     * background finalize can't resurrect an exam the user cancelled mid-flight.
     * Returns the number of rows changed (0 when the exam already moved on).
     */
    @Modifying
    @Transactional
    @Query("update Exam e set e.status = :to where e.id = :id and e.status = :from")
    int updateStatusIfCurrent(@Param("id") UUID id, @Param("from") String from, @Param("to") String to);

    /**
     * User cancellation: revert a still-processing exam to failed. Guarded on the
     * current status so it only affects exams actually mid-parse/mid-evaluate, and
     * so the running background job (which reads this status before finalizing)
     * observes the cancel. Returns 0 when the exam is not in a cancellable state.
     */
    @Modifying
    @Transactional
    @Query("update Exam e set e.status = 'failed', e.parseError = :error, e.parsePhase = null "
        + "where e.id = :id and e.status in ('parsing', 'evaluating')")
    int cancelProcessing(@Param("id") UUID id, @Param("error") String error);

    @Modifying
    @Transactional
    @Query("update Exam e set e.status = 'evaluating', e.parseError = null, e.solverModel = :solverModel where e.id = :id")
    int startEvaluating(@Param("id") UUID id, @Param("solverModel") String solverModel);

    // Only mark failed while still evaluating, so a user cancel (which already
    // set status=failed with its own message) isn't clobbered by a late sweep.
    @Modifying
    @Transactional
    @Query("update Exam e set e.status = 'failed', e.parseError = :error "
        + "where e.id = :id and e.status = 'evaluating'")
    int markSolveFailed(@Param("id") UUID id, @Param("error") String error);
}
