package de.tum.cit.hestia.core.internal.goal;

import de.tum.cit.hestia.core.GoalChange;
import de.tum.cit.hestia.core.GoalKind;
import de.tum.cit.hestia.core.GoalRole;
import de.tum.cit.hestia.core.OrgScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** {@code core.learning_goal_version}: one wording of a goal. Rows are never changed. */
@Entity
@Table(schema = "core", name = "learning_goal_version")
class CoreGoalVersion extends OrgScoped {

    @Embeddable
    record Key(@Column(name = "goal_id") UUID goalId, @Column(name = "version") int version) implements Serializable {}

    @EmbeddedId
    private Key key;

    @Column(nullable = false, updatable = false)
    private String text;

    @Column(name = "short_label", updatable = false)
    private String shortLabel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private GoalKind kind;

    @Enumerated(EnumType.STRING)
    @Column(updatable = false)
    private GoalRole role;

    @Column(name = "bloom_level", updatable = false)
    private String bloomLevel;

    @Column(name = "solo_level", updatable = false)
    private String soloLevel;

    @Column(name = "changed_by", updatable = false)
    private UUID changedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CoreGoalVersion() {}

    CoreGoalVersion(UUID goalId, int version, GoalChange content, UUID changedBy, Instant now) {
        this.key = new Key(goalId, version);
        this.text = content.text();
        this.shortLabel = content.shortLabel();
        this.kind = content.kind();
        this.role = content.role();
        this.bloomLevel = content.bloomLevel();
        this.soloLevel = content.soloLevel();
        this.changedBy = changedBy;
        this.createdAt = now;
    }

    UUID getGoalId() {
        return key.goalId();
    }

    int getVersion() {
        return key.version();
    }

    String getText() {
        return text;
    }

    String getShortLabel() {
        return shortLabel;
    }

    GoalKind getKind() {
        return kind;
    }

    GoalRole getRole() {
        return role;
    }

    String getBloomLevel() {
        return bloomLevel;
    }

    String getSoloLevel() {
        return soloLevel;
    }

    UUID getChangedBy() {
        return changedBy;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    GoalChange content() {
        return new GoalChange(text, shortLabel, kind, role, bloomLevel, soloLevel);
    }

    boolean sameContent(GoalChange change) {
        return Objects.equals(content(), change);
    }
}
