package app.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A long-lived API credential belonging to one user. Only the SHA-256 hash of
 * the token is stored — the plaintext is handed to the caller once at mint time
 * and never persisted, so a database leak does not yield live credentials.
 *
 * <p>{@code userId} is non-null by design: this is the only table the auth
 * filter consults, so a credential can never authenticate without a user behind
 * it.
 */
@Entity
@Table(name = "user_tokens")
public class UserToken {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    private String label;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "last_used_at")
    private OffsetDateTime lastUsedAt;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    /** Usable right now: neither revoked nor past its expiry. */
    public boolean isActive() {
        return revokedAt == null && (expiresAt == null || expiresAt.isAfter(OffsetDateTime.now()));
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(OffsetDateTime lastUsedAt) { this.lastUsedAt = lastUsedAt; }

    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }

    public OffsetDateTime getRevokedAt() { return revokedAt; }
    public void setRevokedAt(OffsetDateTime revokedAt) { this.revokedAt = revokedAt; }
}
