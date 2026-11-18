package de.tum.cit.hestia.core.internal.identity;

import java.io.Serializable;
import java.util.UUID;
import org.springframework.security.core.AuthenticatedPrincipal;

/** The logged-in user as stored in the session. Modules read it only through {@code core.CurrentUser}. */
public record HestiaPrincipal(UUID userId, UUID orgId, String externalId, String displayName, boolean admin)
        implements AuthenticatedPrincipal, Serializable {

    @Override
    public String getName() {
        return externalId;
    }
}
