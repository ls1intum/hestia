package app.parse;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

@Entity
@Table(name = "parse_metrics")
public class ParseMetric {

    @Id
    private UUID id = UUID.randomUUID();

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "exam_id")
    private UUID examId;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(name = "parser_model", nullable = false)
    private String parserModel;

    @Column(name = "pdf_mode")
    private String pdfMode;

    @Column(name = "page_count")
    private Integer pageCount;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "llm_ms")
    private Integer llmMs;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    @Column(nullable = false)
    private boolean success;

    private String error;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public UUID getExamId() { return examId; }
    public void setExamId(UUID examId) { this.examId = examId; }
    public UUID getOwnerId() { return ownerId; }
    public void setOwnerId(UUID ownerId) { this.ownerId = ownerId; }
    public String getParserModel() { return parserModel; }
    public void setParserModel(String parserModel) { this.parserModel = parserModel; }
    public String getPdfMode() { return pdfMode; }
    public void setPdfMode(String pdfMode) { this.pdfMode = pdfMode; }
    public Integer getPageCount() { return pageCount; }
    public void setPageCount(Integer pageCount) { this.pageCount = pageCount; }
    public Integer getDurationMs() { return durationMs; }
    public void setDurationMs(Integer durationMs) { this.durationMs = durationMs; }
    public Integer getLlmMs() { return llmMs; }
    public void setLlmMs(Integer llmMs) { this.llmMs = llmMs; }
    public Integer getPromptTokens() { return promptTokens; }
    public void setPromptTokens(Integer promptTokens) { this.promptTokens = promptTokens; }
    public Integer getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(Integer completionTokens) { this.completionTokens = completionTokens; }
    public Integer getTotalTokens() { return totalTokens; }
    public void setTotalTokens(Integer totalTokens) { this.totalTokens = totalTokens; }
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
}
