package de.tum.cit.hestia.core.internal.tenancy;

import de.tum.cit.hestia.core.OrgContext;
import java.util.UUID;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;

/**
 * Hibernate's tenant: the organisation of {@link OrgContext}, or the nil UUID when the thread has
 * none, which matches no row.
 */
class OrgTenantResolver implements CurrentTenantIdentifierResolver<UUID> {

    static final UUID NO_ORGANISATION = new UUID(0L, 0L);

    @Override
    public UUID resolveCurrentTenantIdentifier() {
        return OrgContext.current().orElse(NO_ORGANISATION);
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }
}
