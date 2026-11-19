package de.tum.cit.hestia.materials.intake;

import de.tum.cit.hestia.core.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Who is calling: the external id that submissions are filed under (the login's
 * eduPersonPrincipalName, which is the name of the logged-in principal) and an e-mail suggestion
 * from {@link CurrentUser#email()}.
 */
@Component
class IntakeRequester {

    private final CurrentUser currentUser;

    IntakeRequester(CurrentUser currentUser) {
        this.currentUser = currentUser;
    }

    record Identity(String id, String suggestedEmail) {
    }

    Identity current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new IllegalStateException("No login");
        }
        return new Identity(auth.getName(), currentUser.email());
    }
}
