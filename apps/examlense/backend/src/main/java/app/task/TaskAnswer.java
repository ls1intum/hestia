package app.task;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "task_answers")
public class TaskAnswer {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    @Column(name = "exam_id", nullable = false)
    private UUID examId;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "selected_option_ids", columnDefinition = "uuid[]", nullable = false)
    private List<UUID> selectedOptionIds = new ArrayList<>();

    @Column(name = "answer_text")
    private String answerText;

    private String reasoning;

    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private String model;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTaskId() { return taskId; }
    public void setTaskId(UUID taskId) { this.taskId = taskId; }
    public UUID getExamId() { return examId; }
    public void setExamId(UUID examId) { this.examId = examId; }
    public List<UUID> getSelectedOptionIds() { return selectedOptionIds; }
    public void setSelectedOptionIds(List<UUID> selectedOptionIds) { this.selectedOptionIds = selectedOptionIds; }
    public String getAnswerText() { return answerText; }
    public void setAnswerText(String answerText) { this.answerText = answerText; }
    public String getReasoning() { return reasoning; }
    public void setReasoning(String reasoning) { this.reasoning = reasoning; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
