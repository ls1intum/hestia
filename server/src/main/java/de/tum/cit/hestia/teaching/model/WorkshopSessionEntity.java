package de.tum.cit.hestia.teaching.model;

import de.tum.cit.hestia.core.OrgScoped;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(schema = "teaching", name = "workshop_sessions")
public class WorkshopSessionEntity extends OrgScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** Who created the session; counts towards the per-user session limit. Access comes from the course role. */
    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    /** The {@code core.course} the session belongs to; its roles decide who may read and change it. */
    @Column(name = "course_id", nullable = false)
    private UUID courseId;

    @Column(name = "display_order")
    private Integer displayOrder;

    /** Human-readable title derived from the session (or first goal, or a default) */
    @Column(columnDefinition = "TEXT")
    private String title;

    @Column(columnDefinition = "TEXT")
    private String studentBackground;

    // ── Promoted WorkshopInput scalars ────────────────────────────────────────
    @Column
    private Integer duration;

    @Column
    private Integer participants;

    @Column(length = 50)
    private String sessionType;

    @Column(length = 100)
    private String sessionTypeOther;


    /** Full generated session JSON stored as a JSONB blob (null while still in draft). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String sessionJson;

    /**
     * Intermediate draft state blob — stores all step inputs as JSON so the user
     * can leave mid-way and resume later. Shape mirrors the client DraftState type.
     * Stored as JSONB in Postgres.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String draftStateJson;

    /** Uploaded PPTX template file. */
    private String templateId;

    /** True when the session should ignore its course's template and use the default design. */
    @Column(name = "use_default_template", nullable = false)
    private boolean useDefaultTemplate = false;

    /** Generated slides stored as JSONB mapping block index to slide arrays. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String slidesJson;

    /**
     * Workflow status:
     *  "draft"    – session is being created (not yet fully generated)
     *  "complete" – full session has been generated and persisted
     */
    @Column(length = 20, nullable = false)
    private String status = "draft";

    /** Which step the user was on when they last saved (e.g. "goals", "skeleton") */
    @Column(length = 50)
    private String currentStep;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // ── Getters & setters ─────────────────────────────────────────

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public UUID getCourseId() { return courseId; }
    public void setCourseId(UUID courseId) { this.courseId = courseId; }

    public Integer getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(Integer displayOrder) { this.displayOrder = displayOrder; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getStudentBackground() { return studentBackground; }
    public void setStudentBackground(String studentBackground) { this.studentBackground = studentBackground; }

    public Integer getDuration() { return duration; }
    public void setDuration(Integer duration) { this.duration = duration; }

    public Integer getParticipants() { return participants; }
    public void setParticipants(Integer participants) { this.participants = participants; }

    public String getSessionType() { return sessionType; }
    public void setSessionType(String sessionType) { this.sessionType = sessionType; }

    public String getSessionTypeOther() { return sessionTypeOther; }
    public void setSessionTypeOther(String sessionTypeOther) { this.sessionTypeOther = sessionTypeOther; }

    public String getSessionJson() { return sessionJson; }
    public void setSessionJson(String sessionJson) { this.sessionJson = sessionJson; }

    public String getDraftStateJson() { return draftStateJson; }
    public void setDraftStateJson(String draftStateJson) { this.draftStateJson = draftStateJson; }

    public String getTemplateId() { return templateId; }
    public void setTemplateId(String templateId) { this.templateId = templateId; }
    public boolean isUseDefaultTemplate() { return useDefaultTemplate; }
    public void setUseDefaultTemplate(boolean useDefaultTemplate) { this.useDefaultTemplate = useDefaultTemplate; }

    public String getSlidesJson() { return slidesJson; }
    public void setSlidesJson(String slidesJson) { this.slidesJson = slidesJson; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCurrentStep() { return currentStep; }
    public void setCurrentStep(String currentStep) { this.currentStep = currentStep; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
