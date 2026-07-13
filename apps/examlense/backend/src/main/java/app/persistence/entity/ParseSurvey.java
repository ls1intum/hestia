package app.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

@Entity
@Table(name = "parse_survey")
public class ParseSurvey {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "exam_id", nullable = false)
    private UUID examId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    private Short speed;

    @Column(name = "content_correctness")
    private Short contentCorrectness;

    private Short structure;

    /**
     * Parser model that produced the surveyed exam, denormalized here at submit
     * time so the by-model rollup survives the exam being deleted (see V2 migration).
     */
    @Column(name = "parser_model")
    private String parserModel;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getExamId() { return examId; }
    public void setExamId(UUID examId) { this.examId = examId; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public Short getSpeed() { return speed; }
    public void setSpeed(Short speed) { this.speed = speed; }
    public Short getContentCorrectness() { return contentCorrectness; }
    public void setContentCorrectness(Short contentCorrectness) { this.contentCorrectness = contentCorrectness; }
    public Short getStructure() { return structure; }
    public void setStructure(Short structure) { this.structure = structure; }
    public String getParserModel() { return parserModel; }
    public void setParserModel(String parserModel) { this.parserModel = parserModel; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
