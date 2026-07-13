package app.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "tasks")
public class Task {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "exam_id", nullable = false)
    private UUID examId;

    @Column(name = "section_id")
    private UUID sectionId;

    @Column(nullable = false)
    private int position;

    private String section;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private String prompt = "";

    @JdbcTypeCode(SqlTypes.JSON)
    private List<TaskOption> options;

    @Column(name = "reference_answer")
    private String referenceAnswer;

    private BigDecimal points;

    @Column(name = "parse_confidence")
    private String parseConfidence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "learning_goal_ids")
    private List<Long> learningGoalIds;

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
    public UUID getSectionId() { return sectionId; }
    public void setSectionId(UUID sectionId) { this.sectionId = sectionId; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
    public String getSection() { return section; }
    public void setSection(String section) { this.section = section; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public List<TaskOption> getOptions() { return options; }
    public void setOptions(List<TaskOption> options) { this.options = options; }
    public String getReferenceAnswer() { return referenceAnswer; }
    public void setReferenceAnswer(String referenceAnswer) { this.referenceAnswer = referenceAnswer; }
    public BigDecimal getPoints() { return points; }
    public void setPoints(BigDecimal points) { this.points = points; }
    public String getParseConfidence() { return parseConfidence; }
    public void setParseConfidence(String parseConfidence) { this.parseConfidence = parseConfidence; }
    public List<Long> getLearningGoalIds() { return learningGoalIds; }
    public void setLearningGoalIds(List<Long> learningGoalIds) { this.learningGoalIds = learningGoalIds; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
