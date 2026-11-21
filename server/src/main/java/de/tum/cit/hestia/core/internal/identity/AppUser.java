package de.tum.cit.hestia.core.internal.identity;

import de.tum.cit.hestia.core.OrgScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A person who logged in once. No e-mail address or other profile attribute is stored. */
@Entity
@Table(schema = "core", name = "app_user")
public class AppUser extends OrgScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** eduPersonPrincipalName, fallback the SAML NameID. */
    @Column(name = "external_id", nullable = false)
    private String externalId;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    protected AppUser() {}

    AppUser(String externalId) {
        this.externalId = externalId;
    }

    public UUID getId() {
        return id;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** Last login; null only for rows created before logins were recorded. */
    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    /** When the inactive period began: the last login, else the account's creation. */
    public Instant lastActivity() {
        return lastSeenAt != null ? lastSeenAt : createdAt;
    }

    void seen(String displayName, Instant now) {
        this.displayName = displayName;
        this.lastSeenAt = now;
    }
}
