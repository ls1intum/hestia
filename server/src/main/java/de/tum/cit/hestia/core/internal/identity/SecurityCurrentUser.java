package de.tum.cit.hestia.core.internal.identity;

import de.tum.cit.hestia.core.CurrentUser;
import java.util.UUID;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** {@link CurrentUser} from the {@link HestiaPrincipal} of the request's security context. */
@Component
class SecurityCurrentUser implements CurrentUser {

    static HestiaPrincipal principal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof HestiaPrincipal principal) {
            return principal;
        }
        throw new AuthenticationCredentialsNotFoundException("Not logged in");
    }

    @Override
    public UUID userId() {
        return principal().userId();
    }

    @Override
    public UUID orgId() {
        return principal().orgId();
    }

    @Override
    public boolean isAdmin() {
        return principal().admin();
    }

    @Override
    public String displayName() {
        return principal().displayName();
    }

    @Override
    public String email() {
        return principal().email();
    }
}
