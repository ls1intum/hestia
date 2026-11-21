package de.tum.cit.hestia.core.internal.security;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.Iterator;
import java.util.Optional;
import org.springframework.security.saml2.provider.service.registration.IterableRelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;

/**
 * The configured registration {@value #HOME} (the TUM login) plus one registration per identity
 * provider of the federation, named {@value #PREFIX} + the base64url of its entity id.
 *
 * <p>Every federation registration is a copy of the service provider side of {@value #HOME}: same
 * entity id, same assertion consumer service and same keys. The service provider stays the one
 * that is registered with the identity providers; only the asserting party differs. Iterating
 * yields the configured registrations only, so the service provider metadata is unchanged.
 */
final class FederatedRegistrations implements IterableRelyingPartyRegistrationRepository {

    static final String HOME = "tum";
    static final String PREFIX = "idp-";

    private final RelyingPartyRegistrationRepository configured;
    private final FederationMetadata federation;

    FederatedRegistrations(RelyingPartyRegistrationRepository configured, FederationMetadata federation) {
        this.configured = configured;
        this.federation = federation;
    }

    static String registrationId(String entityId) {
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(entityId.getBytes(StandardCharsets.UTF_8));
    }

    static Optional<String> entityId(String registrationId) {
        if (registrationId == null || !registrationId.startsWith(PREFIX)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new String(Base64.getUrlDecoder().decode(registrationId.substring(PREFIX.length())),
                    StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    static boolean isFederated(RelyingPartyRegistration registration) {
        return registration.getRegistrationId().startsWith(PREFIX);
    }

    /** The entity id of the home identity provider, if it is configured. */
    Optional<String> homeEntityId() {
        try {
            return Optional.ofNullable(configured.findByRegistrationId(HOME))
                    .map(r -> r.getAssertingPartyMetadata().getEntityId());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    @Override
    public RelyingPartyRegistration findByRegistrationId(String registrationId) {
        Optional<String> entityId = entityId(registrationId);
        if (entityId.isEmpty()) {
            return configured.findByRegistrationId(registrationId);
        }
        return federation.assertingParty(entityId.get())
                .flatMap(party -> Optional.ofNullable(configured.findByRegistrationId(HOME))
                        .map(home -> RelyingPartyRegistration.withAssertingPartyMetadata(party)
                                .registrationId(registrationId)
                                .entityId(fixed(home.getEntityId()))
                                .assertionConsumerServiceLocation(fixed(home.getAssertionConsumerServiceLocation()))
                                .assertionConsumerServiceBinding(home.getAssertionConsumerServiceBinding())
                                .nameIdFormat(home.getNameIdFormat())
                                .authnRequestsSigned(home.isAuthnRequestsSigned())
                                .signingX509Credentials(c -> c.addAll(home.getSigningX509Credentials()))
                                .decryptionX509Credentials(c -> c.addAll(home.getDecryptionX509Credentials()))
                                .build()))
                .orElse(null);
    }

    /** The home registration's URL templates with its id filled in, so they stay the registered URLs. */
    private static String fixed(String template) {
        return template == null ? null : template.replace("{registrationId}", HOME);
    }

    @Override
    public Iterator<RelyingPartyRegistration> iterator() {
        if (configured instanceof Iterable<?> iterable) {
            @SuppressWarnings("unchecked")
            Iterator<RelyingPartyRegistration> it = ((Iterable<RelyingPartyRegistration>) iterable).iterator();
            return it;
        }
        return Collections.emptyIterator();
    }
}
