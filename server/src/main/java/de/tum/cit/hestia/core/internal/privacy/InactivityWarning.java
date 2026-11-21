package de.tum.cit.hestia.core.internal.privacy;

import de.tum.cit.hestia.core.OrgScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A warning that the account will be deleted for inactivity. It belongs to the inactive period that
 * began at {@link #getLastSeenAt()}; a later login starts a new period. Shown to the user after the
 * next login until acknowledged.
 */
@Entity
@Table(schema = "core", name = "inactivity_warning")
class InactivityWarning extends OrgScoped {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "after_days", nullable = false, updatable = false)
    private int afterDays;

    @Column(name = "last_seen_at", nullable = false, updatable = false)
    private Instant lastSeenAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    protected InactivityWarning() {}

    InactivityWarning(UUID userId, int afterDays, Instant lastSeenAt, Instant now) {
        this.userId = userId;
        this.afterDays = afterDays;
        this.lastSeenAt = lastSeenAt;
        this.createdAt = now;
    }

    UUID getUserId() {
        return userId;
    }

    int getAfterDays() {
        return afterDays;
    }

    Instant getLastSeenAt() {
        return lastSeenAt;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    void acknowledge(Instant now) {
        this.acknowledgedAt = now;
    }
}
