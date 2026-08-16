package app.grading;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

@Entity
@Table(name = "task_grades")
public class TaskGrade {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "task_id", nullable = false, unique = true)
    private UUID taskId;

    @Column(name = "exam_id", nullable = false)
    private UUID examId;

    private BigDecimal score;

    @Column(name = "auto_graded", nullable = false)
    private boolean autoGraded = false;

    private String feedback;

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
    public UUID getTaskId() { return taskId; }
    public void setTaskId(UUID taskId) { this.taskId = taskId; }
    public UUID getExamId() { return examId; }
    public void setExamId(UUID examId) { this.examId = examId; }
    public BigDecimal getScore() { return score; }
    public void setScore(BigDecimal score) { this.score = score; }
    public boolean isAutoGraded() { return autoGraded; }
    public void setAutoGraded(boolean autoGraded) { this.autoGraded = autoGraded; }
    public String getFeedback() { return feedback; }
    public void setFeedback(String feedback) { this.feedback = feedback; }
    public UUID getGradedBy() { return gradedBy; }
    public void setGradedBy(UUID gradedBy) { this.gradedBy = gradedBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
