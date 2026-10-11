package app.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A person's account.
 *
 * <p>{@code externalId} starts as a server-generated {@code anon-…} handle, so an
 * account can be created on first visit with nothing asked of the user. They may
 * later replace it with their TUM ID — the same identifier TUM's Shibboleth IdP
 * will release, which is what lets a future SAML login land on this exact row
 * and inherit its exams.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "external_id", nullable = false, unique = true)
    private String externalId;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "is_admin", nullable = false)
    private boolean admin = false;

    @Column(name = "created_ip_hash")
    private String createdIpHash;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "last_seen_at")
    private OffsetDateTime lastSeenAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public boolean isAdmin() { return admin; }
    public void setAdmin(boolean admin) { this.admin = admin; }

    public String getCreatedIpHash() { return createdIpHash; }
    public void setCreatedIpHash(String createdIpHash) { this.createdIpHash = createdIpHash; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(OffsetDateTime lastSeenAt) { this.lastSeenAt = lastSeenAt; }
}
