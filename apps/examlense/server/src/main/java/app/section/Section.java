package app.section;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

@Entity
@Table(name = "sections")
public class Section {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "exam_id", nullable = false)
    private UUID examId;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false)
    private String name = "";

    @Column(name = "confirmed_at")
    private OffsetDateTime confirmedAt;

    @Column(name = "solve_started_at")
    private OffsetDateTime solveStartedAt;

    @Column(name = "goals_started_at")
    private OffsetDateTime goalsStartedAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getExamId() { return examId; }
    public void setExamId(UUID examId) { this.examId = examId; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public OffsetDateTime getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(OffsetDateTime confirmedAt) { this.confirmedAt = confirmedAt; }
    public OffsetDateTime getSolveStartedAt() { return solveStartedAt; }
    public void setSolveStartedAt(OffsetDateTime solveStartedAt) { this.solveStartedAt = solveStartedAt; }
    public OffsetDateTime getGoalsStartedAt() { return goalsStartedAt; }
    public void setGoalsStartedAt(OffsetDateTime goalsStartedAt) { this.goalsStartedAt = goalsStartedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
