package de.tum.cit.hestia.materials.intake;

import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal;
import org.springframework.stereotype.Component;

/**
 * Who is calling: the external id that submissions are filed under, whether they are a platform
 * admin, and an e-mail suggestion from the login. Until package 4 brings {@code core.CurrentUser},
 * the id is the SAML attribute eduPersonPrincipalName, falling back to the authentication name
 * (the development login's {@code dev-local-user}).
 */
@Component
class IntakeRequester {

    static final String EPPN = "urn:oid:1.3.6.1.4.1.5923.1.1.1.6";
    static final String MAIL = "urn:oid:0.9.2342.19200300.100.1.3";

    private final Set<String> platformAdmins;

    IntakeRequester(@Value("${hestia.security.platform-admins:}") List<String> platformAdmins) {
        this.platformAdmins = Set.copyOf(platformAdmins.stream().map(String::trim).filter(s -> !s.isEmpty()).toList());
    }

    record Identity(String id, boolean admin, String suggestedEmail) {
    }

    Identity current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new IllegalStateException("No login");
        }
        String id = auth.getName();
        String mail = null;
        if (auth.getPrincipal() instanceof Saml2AuthenticatedPrincipal saml) {
            String eppn = saml.getFirstAttribute(EPPN);
            if (eppn != null && !eppn.isBlank()) {
                id = eppn;
            }
            mail = saml.getFirstAttribute(MAIL);
        }
        return new Identity(id, platformAdmins.contains(id), mail);
    }
}
