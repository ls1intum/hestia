package de.tum.cit.hestia.core.internal.security;

import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import org.opensaml.core.xml.config.XMLObjectProviderRegistrySupport;
import org.opensaml.saml.common.xml.SAMLConstants;
import org.springframework.security.saml2.core.Saml2Error;
import org.springframework.security.saml2.core.Saml2ErrorCodes;
import org.springframework.security.saml2.core.Saml2ParameterNames;
import org.springframework.security.saml2.provider.service.authentication.AbstractSaml2AuthenticationRequest;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticationException;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticationToken;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.web.OpenSaml4AuthenticationTokenConverter;
import org.springframework.security.saml2.provider.service.web.RelyingPartyRegistrationPlaceholderResolvers;
import org.springframework.security.saml2.provider.service.web.Saml2AuthenticationRequestRepository;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Picks the registration for a SAML response at the one assertion consumer service
 * ({@code /login/saml2/sso/tum}). A response whose issuer is an identity provider of the federation
 * gets that provider's registration; everything else (the TUM login) goes to Spring's converter as
 * before.
 *
 * <p>The issuer decides, not the stored authentication request (which a forged RelayState could
 * point at another provider's registration). Trusting the
 * unverified issuer here is safe: the response is then verified with that provider's keys from the
 * signed federation metadata, and {@link SamlLogin} checks the organisation against its scopes.
 */
final class FederatedTokenConverter implements AuthenticationConverter {

    private final FederatedRegistrations registrations;
    private final OpenSaml4AuthenticationTokenConverter delegate;
    private final Saml2AuthenticationRequestRepository<AbstractSaml2AuthenticationRequest> requests;

    FederatedTokenConverter(FederatedRegistrations registrations,
            Saml2AuthenticationRequestRepository<AbstractSaml2AuthenticationRequest> requests) {
        this.registrations = registrations;
        this.requests = requests;
        this.delegate = new OpenSaml4AuthenticationTokenConverter(registrations);
        this.delegate.setAuthenticationRequestRepository(requests);
    }

    @Override
    public Saml2AuthenticationToken convert(HttpServletRequest request) {
        String encoded = request.getParameter(Saml2ParameterNames.SAML_RESPONSE);
        if (encoded == null || !"POST".equalsIgnoreCase(request.getMethod())) {
            return delegate.convert(request);
        }
        String xml = decode(encoded);
        Optional<RelyingPartyRegistration> federated = issuer(xml)
                .filter(issuer -> !registrations.homeEntityId().map(issuer::equals).orElse(false))
                .map(issuer -> registrations.findByRegistrationId(FederatedRegistrations.registrationId(issuer)));
        if (federated.isEmpty()) {
            return delegate.convert(request);
        }
        RelyingPartyRegistration registration = federated.get();
        var uris = RelyingPartyRegistrationPlaceholderResolvers.uriResolver(request, registration);
        registration = registration.mutate()
                .entityId(uris.resolve(registration.getEntityId()))
                .assertionConsumerServiceLocation(uris.resolve(registration.getAssertionConsumerServiceLocation()))
                .build();
        AbstractSaml2AuthenticationRequest stored = requests.loadAuthenticationRequest(request);
        if (stored != null && !registration.getRegistrationId().equals(stored.getRelyingPartyRegistrationId())) {
            stored = null;
        }
        return new Saml2AuthenticationToken(registration, xml, stored);
    }

    private static String decode(String encoded) {
        try {
            return new String(Base64.getMimeDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new Saml2AuthenticationException(new Saml2Error(Saml2ErrorCodes.INVALID_RESPONSE, "Not base64"), e);
        }
    }

    /** The {@code saml:Issuer} directly under the response element, unverified. */
    static Optional<String> issuer(String xml) {
        try {
            Element response = XMLObjectProviderRegistrySupport.getParserPool()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
            for (Node n = response.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n instanceof Element e && "Issuer".equals(e.getLocalName())
                        && SAMLConstants.SAML20_NS.equals(e.getNamespaceURI())) {
                    return Optional.of(e.getTextContent().trim()).filter(s -> !s.isEmpty());
                }
            }
            return Optional.empty();
        } catch (Exception e) {
            throw new Saml2AuthenticationException(
                    new Saml2Error(Saml2ErrorCodes.MALFORMED_RESPONSE_DATA, "Unreadable SAML response"), e);
        }
    }
}
