package de.tum.cit.hestia.core.internal.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A university, the tenant. The only table without {@code org_id}. */
@Entity
@Table(schema = "core", name = "organisation")
public class Organisation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "schac_home_organization", nullable = false, unique = true)
    private String schacHomeOrganization;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected Organisation() {}

    Organisation(String schacHomeOrganization) {
        this.schacHomeOrganization = schacHomeOrganization;
    }

    public UUID getId() {
        return id;
    }

    public String getSchacHomeOrganization() {
        return schacHomeOrganization;
    }
}
