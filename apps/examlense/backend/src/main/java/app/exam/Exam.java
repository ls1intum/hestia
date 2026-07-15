package app.exam;

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
@Table(name = "exams")
public class Exam {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(nullable = false)
    private String title = "";

    private String course;
    private String semester;

    @Column(name = "instructor_name")
    private String instructorName;

    @Column(name = "total_points")
    private BigDecimal totalPoints;

    @Column(nullable = false)
    private String language = "en";

    @Column(nullable = false)
    private String source;

    @Column(name = "source_file_url")
    private String sourceFileUrl;

    @Column(nullable = false)
    private String status = "draft";

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "parse_error")
    private String parseError;

    @Column(name = "parse_phase")
    private String parsePhase;

    @Column(name = "parser_model")
    private String parserModel;

    @Column(name = "solver_model")
    private String solverModel;

    @Column(name = "parse_raw_text")
    private String parseRawText;

    @Column(name = "lgh_course_id")
    private Long lghCourseId;

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
    public String getCourse() { return course; }
    public void setCourse(String course) { this.course = course; }
    public String getSemester() { return semester; }
    public void setSemester(String semester) { this.semester = semester; }
    public String getInstructorName() { return instructorName; }
    public void setInstructorName(String instructorName) { this.instructorName = instructorName; }
    public BigDecimal getTotalPoints() { return totalPoints; }
    public void setTotalPoints(BigDecimal totalPoints) { this.totalPoints = totalPoints; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getSourceFileUrl() { return sourceFileUrl; }
    public void setSourceFileUrl(String sourceFileUrl) { this.sourceFileUrl = sourceFileUrl; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public UUID getOwnerId() { return ownerId; }
    public void setOwnerId(UUID ownerId) { this.ownerId = ownerId; }
    public String getParseError() { return parseError; }
    public void setParseError(String parseError) { this.parseError = parseError; }
    public String getParsePhase() { return parsePhase; }
    public void setParsePhase(String parsePhase) { this.parsePhase = parsePhase; }
    public String getParserModel() { return parserModel; }
    public void setParserModel(String parserModel) { this.parserModel = parserModel; }
    public String getSolverModel() { return solverModel; }
    public void setSolverModel(String solverModel) { this.solverModel = solverModel; }
    public String getParseRawText() { return parseRawText; }
    public void setParseRawText(String parseRawText) { this.parseRawText = parseRawText; }
    public Long getLghCourseId() { return lghCourseId; }
    public void setLghCourseId(Long lghCourseId) { this.lghCourseId = lghCourseId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
