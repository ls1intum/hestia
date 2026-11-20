package de.tum.cit.hestia.assessment.examination;

import jakarta.persistence.Column;
import de.tum.cit.hestia.core.OrgScoped;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

@Entity
@Table(schema = "assessment", name = "exams")
public class Examination extends OrgScoped {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(nullable = false)
    private String title = "";

    @Column(nullable = false)
    private String source;

    @Column(name = "source_file_url")
    private String sourceFileUrl;

    @Column(nullable = false)
    private String status = "draft";

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "course_id", nullable = false)
    private UUID courseId;

    @Column(name = "parse_error")
    private String parseError;

    @Column(name = "parse_phase")
    private String parsePhase;

    @Column(name = "parser_model")
    private String parserModel;

    @Column(name = "solver_model")
    private String solverModel;

    @Column(name = "page_count")
    private Integer pageCount;

    // Set at the start of every parse attempt (initial + retry) so the client
    // can anchor its progress countdown to the current attempt, not created_at.
    @Column(name = "parse_started_at")
    private OffsetDateTime parseStartedAt;

    // Stamped once when parsing successfully finalizes (parsing→draft). Lets the
    // UI distinguish a parse failure (never set) from an evaluation failure
    // (set, then failed while solving) without inferring from task count —
    // tasks are committed before finalize, so a failure in that window would
    // otherwise look like a solvable exam. Null until the first successful parse.
    @Column(name = "parsed_at")
    private OffsetDateTime parsedAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getSourceFileUrl() { return sourceFileUrl; }
    public void setSourceFileUrl(String sourceFileUrl) { this.sourceFileUrl = sourceFileUrl; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public UUID getCourseId() { return courseId; }
    public void setCourseId(UUID courseId) { this.courseId = courseId; }
    public String getParseError() { return parseError; }
    public void setParseError(String parseError) { this.parseError = parseError; }
    public String getParsePhase() { return parsePhase; }
    public void setParsePhase(String parsePhase) { this.parsePhase = parsePhase; }
    public String getParserModel() { return parserModel; }
    public void setParserModel(String parserModel) { this.parserModel = parserModel; }
    public String getSolverModel() { return solverModel; }
    public void setSolverModel(String solverModel) { this.solverModel = solverModel; }
    public Integer getPageCount() { return pageCount; }
    public void setPageCount(Integer pageCount) { this.pageCount = pageCount; }
    public OffsetDateTime getParseStartedAt() { return parseStartedAt; }
    public void setParseStartedAt(OffsetDateTime parseStartedAt) { this.parseStartedAt = parseStartedAt; }
    public OffsetDateTime getParsedAt() { return parsedAt; }
    public void setParsedAt(OffsetDateTime parsedAt) { this.parsedAt = parsedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
