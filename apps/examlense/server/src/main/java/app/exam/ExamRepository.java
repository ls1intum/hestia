package app.exam;

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
    @Query("update Exam e set e.parserModel = :model where e.id = :id")
    int updateParserModel(@Param("id") UUID id, @Param("model") String model);

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
     * User cancellation of a mid-parse exam: revert to {@code failed} with the
     * cancel message so the editor offers a re-parse — there is no completed
     * structure worth preserving.
     */
    @Modifying
    @Transactional
    @Query("update Exam e set e.status = 'failed', e.parseError = :error, e.parsePhase = null "
        + "where e.id = :id and e.status = 'parsing'")
    int cancelParsing(@Param("id") UUID id, @Param("error") String error);

    /**
     * User cancellation of a mid-solve exam: revert to {@code ready}, not
     * {@code failed} — the parsed/edited structure is intact, so a deliberate
     * cancel belongs back in the editor rather than in an error state. Leaving
     * {@code evaluating} is also what locks out the running solve job, whose
     * finalize is CAS'd on {@code evaluating}.
     */
    @Modifying
    @Transactional
    @Query("update Exam e set e.status = 'ready', e.parseError = null, e.parsePhase = null "
        + "where e.id = :id and e.status = 'evaluating'")
    int cancelEvaluating(@Param("id") UUID id);

    /**
     * Start (or retry) evaluation. Compare-and-set on the eligible statuses: a
     * fresh send is already {@code evaluating} (the client flips it up front) and
     * a retry starts from {@code failed}. Crucially this is NOT unconditional — a
     * cancel reverts to {@code ready}, so a stale fire-and-forget dispatch landing
     * here after the cancel matches no row and the caller bails, instead of
     * resurrecting the exam and wiping its answers.
     */
    @Modifying
    @Transactional
    @Query("update Exam e set e.status = 'evaluating', e.parseError = null, e.solverModel = :solverModel "
        + "where e.id = :id and e.status in ('evaluating', 'failed')")
    int startEvaluating(@Param("id") UUID id, @Param("solverModel") String solverModel);

    // Only mark failed while still evaluating, so a user cancel (which reverts the
    // exam to `ready`) isn't clobbered back to failed by a late sweep.
    @Modifying
    @Transactional
    @Query("update Exam e set e.status = 'failed', e.parseError = :error "
        + "where e.id = :id and e.status = 'evaluating'")
    int markSolveFailed(@Param("id") UUID id, @Param("error") String error);
}
