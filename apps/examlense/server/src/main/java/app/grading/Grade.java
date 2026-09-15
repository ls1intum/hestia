package app.grading;

import app.taskblock.AIAnswer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

@Entity
@Table(name = "task_grades")
public class Grade {

    @Id
    private UUID id = UUID.randomUUID();

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "answer_id", nullable = false, unique = true)
    private AIAnswer answer;

    private BigDecimal score;

    @Column(name = "auto_graded", nullable = false)
    private boolean autoGraded = false;


    @Column(name = "graded_by")
    private UUID gradedBy;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public AIAnswer getAnswer() { return answer; }
    public void setAnswer(AIAnswer answer) { this.answer = answer; }
    public BigDecimal getScore() { return score; }
    public void setScore(BigDecimal score) { this.score = score; }
    public boolean isAutoGraded() { return autoGraded; }
    public void setAutoGraded(boolean autoGraded) { this.autoGraded = autoGraded; }
    public UUID getGradedBy() { return gradedBy; }
    public void setGradedBy(UUID gradedBy) { this.gradedBy = gradedBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
