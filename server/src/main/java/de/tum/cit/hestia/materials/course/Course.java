package de.tum.cit.hestia.materials.course;

import java.util.UUID;
import de.tum.cit.hestia.core.OrgScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(schema = "materials", name = "course")
public class Course extends OrgScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "output_language", length = 16)
    private String outputLanguage;

    @Column(name = "figures_enabled", nullable = false)
    private boolean figuresEnabled = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** When the instructor dismissed the one-time skill review; null while it is still due. */
    @Column(name = "skills_reviewed_at")
    private OffsetDateTime skillsReviewedAt;

    /** The {@code core.course} this course belongs to; course roles are checked on it. */
    @Column(name = "core_course_id", nullable = false, updatable = false)
    private UUID coreCourseId;

    protected Course() {
    }

    public Course(String name, UUID coreCourseId) {
        this.name = name;
        this.coreCourseId = coreCourseId;
    }

    public UUID getCoreCourseId() {
        return coreCourseId;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getSkillsReviewedAt() {
        return skillsReviewedAt;
    }

    public void setSkillsReviewedAt(OffsetDateTime skillsReviewedAt) {
        this.skillsReviewedAt = skillsReviewedAt;
    }
}
