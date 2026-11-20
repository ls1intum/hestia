package de.tum.cit.hestia.core.internal.goal;

import de.tum.cit.hestia.core.DomainModule;
import de.tum.cit.hestia.core.OrgScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** {@code core.alignment_link}: a goal linked to an object of a domain module. */
@Entity
@Table(schema = "core", name = "alignment_link")
class CoreAlignmentLink extends OrgScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "course_id", nullable = false, updatable = false)
    private UUID courseId;

    @Column(name = "goal_id", nullable = false, updatable = false)
    private UUID goalId;

    @Convert(converter = DomainModuleConverter.class)
    @Column(name = "target_module", nullable = false, updatable = false)
    private DomainModule targetModule;

    @Column(name = "target_type", nullable = false, updatable = false)
    private String targetType;

    @Column(name = "target_id", nullable = false, updatable = false)
    private String targetId;

    @Column(name = "goal_version_seen", nullable = false)
    private int goalVersionSeen;

    @Column(name = "review_state", nullable = false)
    private String reviewState = OK;

    @Column(name = "review_reason")
    private String reviewReason;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CoreAlignmentLink() {}

    CoreAlignmentLink(UUID courseId, UUID goalId, DomainModule targetModule, String targetType, String targetId,
            int goalVersionSeen, UUID createdBy, Instant now) {
        this.courseId = courseId;
        this.goalId = goalId;
        this.targetModule = targetModule;
        this.targetType = targetType;
        this.targetId = targetId;
        this.goalVersionSeen = goalVersionSeen;
        this.createdBy = createdBy;
        this.createdAt = now;
    }

    static final String OK = "OK";
    static final String REVIEW = "REVIEW";
    static final String GOAL_CHANGED = "GOAL_CHANGED";
    static final String GOAL_REMOVED = "GOAL_REMOVED";

    /** Marks the link for review; a removed goal stays the reason once it was given. */
    void markForReview(String reason) {
        if (GOAL_REMOVED.equals(reviewReason) && REVIEW.equals(reviewState)) {
            return;
        }
        reviewState = REVIEW;
        reviewReason = reason;
    }

    /** The link was checked against the goal's current version. */
    void confirm(int currentGoalVersion) {
        reviewState = OK;
        reviewReason = null;
        goalVersionSeen = currentGoalVersion;
    }

    UUID getId() {
        return id;
    }

    UUID getCourseId() {
        return courseId;
    }

    UUID getGoalId() {
        return goalId;
    }

    DomainModule getTargetModule() {
        return targetModule;
    }

    String getTargetType() {
        return targetType;
    }

    String getTargetId() {
        return targetId;
    }

    int getGoalVersionSeen() {
        return goalVersionSeen;
    }

    String getReviewState() {
        return reviewState;
    }

    String getReviewReason() {
        return reviewReason;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
