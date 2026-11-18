package de.tum.cit.hestia.core;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * Superclass of every entity whose table has {@code org_id}. Hibernate fills the column on insert
 * from {@link OrgContext} and adds it to every query, so an entity of another organisation is never
 * loaded. Row-level security in the database is the backstop for native SQL.
 */
@MappedSuperclass
public abstract class OrgScoped {

    @TenantId
    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    public UUID getOrgId() {
        return orgId;
    }
}
