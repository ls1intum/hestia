package de.tum.cit.hestia.core.internal.goal;

import de.tum.cit.hestia.core.DomainModule;
import de.tum.cit.hestia.core.GoalOrigin;
import de.tum.cit.hestia.core.GoalStatus;
import de.tum.cit.hestia.core.OrgScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** {@code core.learning_goal}: the stable identity of a goal; its wording is in {@link CoreGoalVersion}. */
@Entity
@Table(schema = "core", name = "learning_goal")
class CoreGoal extends OrgScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "course_id", nullable = false, updatable = false)
    private UUID courseId;

    @Column(name = "current_version", nullable = false)
    private int currentVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GoalStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private GoalOrigin origin;

    @Column(name = "creation_provenance", updatable = false)
    private String creationProvenance;

    @Convert(converter = DomainModuleConverter.class)
    @Column(name = "created_via", nullable = false, updatable = false)
    private DomainModule createdVia;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "removed_at")
    private Instant removedAt;

    protected CoreGoal() {}

    CoreGoal(UUID courseId, GoalStatus status, GoalOrigin origin, String creationProvenance,
            DomainModule createdVia, UUID createdBy, Instant now) {
        this.courseId = courseId;
        this.currentVersion = 1;
        this.status = status;
        this.origin = origin;
        this.creationProvenance = creationProvenance;
        this.createdVia = createdVia;
        this.createdBy = createdBy;
        this.createdAt = now;
    }

    UUID getId() {
        return id;
    }

    UUID getCourseId() {
        return courseId;
    }

    int getCurrentVersion() {
        return currentVersion;
    }

    void setCurrentVersion(int currentVersion) {
        this.currentVersion = currentVersion;
    }

    GoalStatus getStatus() {
        return status;
    }

    void setStatus(GoalStatus status) {
        this.status = status;
    }

    GoalOrigin getOrigin() {
        return origin;
    }

    String getCreationProvenance() {
        return creationProvenance;
    }

    DomainModule getCreatedVia() {
        return createdVia;
    }

    UUID getCreatedBy() {
        return createdBy;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getRemovedAt() {
        return removedAt;
    }

    void setRemovedAt(Instant removedAt) {
        this.removedAt = removedAt;
    }
}
