package de.tum.cit.hestia.core.internal.goal;

import de.tum.cit.hestia.core.OrgScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** {@code core.goal_relationship}: a directed, typed relationship between two goals. */
@Entity
@Table(schema = "core", name = "goal_relationship")
class CoreGoalRelationship extends OrgScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "source_goal_id", nullable = false, updatable = false)
    private UUID sourceGoalId;

    @Column(name = "target_goal_id", nullable = false, updatable = false)
    private UUID targetGoalId;

    @Column(nullable = false, updatable = false)
    private String type;

    @Column(nullable = false, updatable = false)
    private double confidence;

    @Column(nullable = false, updatable = false)
    private String origin;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CoreGoalRelationship() {}

    CoreGoalRelationship(UUID sourceGoalId, UUID targetGoalId, String type, double confidence, String origin,
            Instant now) {
        this.sourceGoalId = sourceGoalId;
        this.targetGoalId = targetGoalId;
        this.type = type;
        this.confidence = confidence;
        this.origin = origin;
        this.createdAt = now;
    }

    UUID getId() {
        return id;
    }

    UUID getSourceGoalId() {
        return sourceGoalId;
    }

    UUID getTargetGoalId() {
        return targetGoalId;
    }

    String getType() {
        return type;
    }

    double getConfidence() {
        return confidence;
    }

    String getOrigin() {
        return origin;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
