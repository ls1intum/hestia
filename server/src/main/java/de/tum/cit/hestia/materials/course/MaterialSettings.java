package de.tum.cit.hestia.materials.course;

import de.tum.cit.hestia.core.OrgScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/** What the materials module keeps per {@code core.course}: how its material is processed. */
@Entity
@Table(schema = "materials", name = "material_settings")
public class MaterialSettings extends OrgScoped {

    @Id
    @Column(name = "course_id", nullable = false, updatable = false)
    private UUID courseId;

    @Column(name = "output_language", length = 16)
    private String outputLanguage;

    @Column(name = "figures_enabled", nullable = false)
    private boolean figuresEnabled = false;

    /** When the instructor dismissed the one-time skill review; null while it is still due. */
    @Column(name = "skills_reviewed_at")
    private OffsetDateTime skillsReviewedAt;

    protected MaterialSettings() {
    }

    public UUID getCourseId() {
        return courseId;
    }

    public String getOutputLanguage() {
        return outputLanguage;
    }

    public void setOutputLanguage(String outputLanguage) {
        this.outputLanguage = outputLanguage;
    }

    public boolean isFiguresEnabled() {
        return figuresEnabled;
    }

    public void setFiguresEnabled(boolean figuresEnabled) {
        this.figuresEnabled = figuresEnabled;
    }

    public OffsetDateTime getSkillsReviewedAt() {
        return skillsReviewedAt;
    }

    public void setSkillsReviewedAt(OffsetDateTime skillsReviewedAt) {
        this.skillsReviewedAt = skillsReviewedAt;
    }
}
