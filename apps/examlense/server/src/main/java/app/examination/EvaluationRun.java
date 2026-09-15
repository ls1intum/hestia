package app.examination;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * One AI attempt at an examination: which model answered it, how hard it was
 * asked to think, and when.
 *
 * <p>At most one row per examination. Starting an evaluation wipes the previous
 * answers and their grades, so there is never a second live answer set that a
 * second run could explain; re-solving replaces this row rather than adding to
 * it. Comparing two models is done by duplicating the examination.
 */
@Entity
@Table(name = "evaluation_runs")
public class EvaluationRun {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "exam_id", nullable = false)
    private UUID examId;

    /** Catalog id of the solver, e.g. {@code claude-opus-4-8}. */
    @Column(name = "solver_model", nullable = false)
    private String solverModel;

    @Column(name = "thinking_level")
    private String thinkingLevel;

    @Generated(event = EventType.INSERT)
    @Column(name = "started_at", insertable = false, updatable = false)
    private OffsetDateTime startedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getExamId() { return examId; }
    public void setExamId(UUID examId) { this.examId = examId; }
    public String getSolverModel() { return solverModel; }
    public void setSolverModel(String solverModel) { this.solverModel = solverModel; }
    public String getThinkingLevel() { return thinkingLevel; }
    public void setThinkingLevel(String thinkingLevel) { this.thinkingLevel = thinkingLevel; }
    public OffsetDateTime getStartedAt() { return startedAt; }
}
