package de.tum.cit.hestia.teaching.model;

import de.tum.cit.hestia.core.OrgScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * What Workshopper keeps for a course besides the {@code core.course} itself: its place on the
 * dashboard, the settings the course wizard stores and the slide template its sessions inherit.
 * Created lazily, the first time Workshopper needs one for a course.
 */
@Entity
@Table(schema = "teaching", name = "teaching_plan")
public class TeachingPlanEntity extends OrgScoped {

    @Id
    @Column(name = "course_id")
    private UUID courseId;

    @Column(name = "display_order")
    private Integer displayOrder;

    /** The course settings of the wizard ({@code workshopInput}), as JSON. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "draft_state_json", columnDefinition = "jsonb")
    private String draftStateJson;

    @Column(length = 20)
    private String status = "complete";

    @Column(name = "current_step", length = 50)
    private String currentStep;

    @Column(name = "template_id", length = 255)
    private String templateId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    protected TeachingPlanEntity() {}

    public TeachingPlanEntity(UUID courseId) {
        this.courseId = courseId;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public UUID getCourseId() { return courseId; }

    public Integer getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(Integer displayOrder) { this.displayOrder = displayOrder; }

    public String getDraftStateJson() { return draftStateJson; }
    public void setDraftStateJson(String draftStateJson) { this.draftStateJson = draftStateJson; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCurrentStep() { return currentStep; }
    public void setCurrentStep(String currentStep) { this.currentStep = currentStep; }

    public String getTemplateId() { return templateId; }
    public void setTemplateId(String templateId) { this.templateId = templateId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
