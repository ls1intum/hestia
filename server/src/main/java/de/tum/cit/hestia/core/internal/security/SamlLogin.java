package de.tum.cit.hestia.core.internal.security;

import de.tum.cit.hestia.core.internal.identity.HestiaAuthentication;
import de.tum.cit.hestia.core.internal.identity.HestiaPrincipal;
import de.tum.cit.hestia.core.internal.identity.IdentityService;
import java.util.Optional;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.saml2.core.Saml2Error;
import org.springframework.security.saml2.core.Saml2ErrorCodes;
import org.springframework.security.saml2.provider.service.authentication.OpenSaml4AuthenticationProvider;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticationException;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticationToken;

/**
 * Turns a valid SAML assertion into a Hestia login. The organisation is the attribute
 * schacHomeOrganization, falling back to the scope of eduPersonPrincipalName; without either the
 * login is rejected. The user is identified by eduPersonPrincipalName, falling back to the NameID.
 */
class SamlLogin implements AuthenticationProvider {

    static final String SCHAC_HOME_ORGANIZATION = "urn:oid:1.3.6.1.4.1.25178.1.2.9";
    static final String EDU_PERSON_PRINCIPAL_NAME = "urn:oid:1.3.6.1.4.1.5923.1.1.1.6";
    static final String DISPLAY_NAME = "urn:oid:2.16.840.1.113730.3.1.241";
    static final String MAIL = "urn:oid:0.9.2342.19200300.100.1.3";

    private final OpenSaml4AuthenticationProvider assertions = new OpenSaml4AuthenticationProvider();
    private final IdentityService identities;

    SamlLogin(IdentityService identities) {
        this.identities = identities;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        Authentication verified = assertions.authenticate(authentication);
        if (!(verified.getPrincipal() instanceof Saml2AuthenticatedPrincipal saml)) {
            throw rejected("No SAML principal");
        }
        HestiaPrincipal principal = principal(saml.getName(),
                saml.getFirstAttribute(SCHAC_HOME_ORGANIZATION),
                saml.getFirstAttribute(EDU_PERSON_PRINCIPAL_NAME),
                saml.getFirstAttribute(DISPLAY_NAME),
                saml.getFirstAttribute(MAIL));
        return new HestiaAuthentication(principal);
    }

    HestiaPrincipal principal(String nameId, Object schacHome, Object eppn, Object displayName) {
        return principal(nameId, schacHome, eppn, displayName, null);
    }

    HestiaPrincipal principal(String nameId, Object schacHome, Object eppn, Object displayName, Object mail) {
        String principalName = text(eppn).orElse(null);
        String organisation = text(schacHome)
                .or(() -> Optional.ofNullable(principalName)
                        .filter(n -> n.indexOf('@') > 0 && n.indexOf('@') < n.length() - 1)
                        .map(n -> n.substring(n.lastIndexOf('@') + 1)))
                .orElseThrow(() -> rejected("The identity provider sent no organisation"));
        String externalId = Optional.ofNullable(principalName).or(() -> text(nameId))
                .orElseThrow(() -> rejected("The identity provider sent no user id"));
        return identities.login(organisation, externalId, text(displayName).orElse(null), text(mail).orElse(null));
    }

    private static Optional<String> text(Object value) {
        return Optional.ofNullable(value).map(Object::toString).map(String::trim).filter(s -> !s.isEmpty());
    }

    private static Saml2AuthenticationException rejected(String message) {
        return new Saml2AuthenticationException(new Saml2Error(Saml2ErrorCodes.INVALID_ASSERTION, message));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return Saml2AuthenticationToken.class.isAssignableFrom(authentication);
    }
}
