package de.tum.cit.hestia.core.internal.security;

import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.Collator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.xml.namespace.QName;
import net.shibboleth.utilities.java.support.resolver.CriteriaSet;
import org.opensaml.core.xml.XMLObject;
import org.opensaml.core.xml.config.XMLObjectProviderRegistrySupport;
import org.opensaml.core.xml.schema.XSAny;
import org.opensaml.core.xml.schema.XSString;
import org.opensaml.saml.common.xml.SAMLConstants;
import org.opensaml.saml.criterion.EntityRoleCriterion;
import org.opensaml.saml.ext.saml2mdattr.EntityAttributes;
import org.opensaml.saml.ext.saml2mdui.UIInfo;
import org.opensaml.saml.metadata.resolver.filter.MetadataFilter;
import org.opensaml.saml.metadata.resolver.filter.MetadataFilterChain;
import org.opensaml.saml.metadata.resolver.filter.impl.RequiredValidUntilFilter;
import org.opensaml.saml.metadata.resolver.filter.impl.SignatureValidationFilter;
import org.opensaml.saml.metadata.resolver.impl.DOMMetadataResolver;
import org.opensaml.saml.metadata.resolver.index.impl.RoleMetadataIndex;
import org.opensaml.saml.saml2.core.Attribute;
import org.opensaml.saml.saml2.metadata.EntityDescriptor;
import org.opensaml.saml.saml2.metadata.Extensions;
import org.opensaml.saml.saml2.metadata.IDPSSODescriptor;
import org.opensaml.saml.saml2.metadata.Organization;
import org.opensaml.security.credential.Credential;
import org.opensaml.security.credential.impl.CollectionCredentialResolver;
import org.opensaml.security.x509.BasicX509Credential;
import org.opensaml.xmlsec.config.impl.DefaultSecurityConfigurationBootstrap;
import org.opensaml.xmlsec.signature.support.impl.ExplicitKeySignatureTrustEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.saml2.core.OpenSamlInitializationService;
import org.springframework.security.saml2.provider.service.registration.AssertingPartyMetadata;
import org.springframework.security.saml2.provider.service.registration.OpenSaml4AssertingPartyMetadataRepository;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;

/**
 * The identity providers of a SAML federation (DFN-AAI), from its signed metadata aggregate. Off
 * unless {@code hestia.federation.enabled}; then only the configured TUM login exists.
 *
 * <p>The aggregate is accepted only if its root carries a valid signature by the configured
 * federation signing certificate and its {@code validUntil} lies in the future (and at most
 * {@link #MAX_VALIDITY} ahead). It is reloaded every {@code hestia.federation.refresh}; a failed
 * reload keeps the last good copy. A failed first load leaves the federation empty (the TUM login
 * keeps working) and is retried after {@link #RETRY}.
 */
@Component
class FederationMetadata {

    private static final Logger log = LoggerFactory.getLogger(FederationMetadata.class);

    /** Signed metadata that claims to stay valid longer than this is refused (DFN signs for about a week). */
    static final Duration MAX_VALIDITY = Duration.ofDays(28);
    static final Duration RETRY = Duration.ofMinutes(5);
    private static final long MAX_BYTES = 100L * 1024 * 1024;

    static final String SHIBMD_NS = "urn:mace:shibboleth:metadata:1.0";
    static final QName SCOPE = new QName(SHIBMD_NS, "Scope");
    static final String ENTITY_CATEGORY = "http://macedir.org/entity-category";
    static final String HIDE_FROM_DISCOVERY = "http://refeds.org/category/hide-from-discovery";

    /** One identity provider of the federation. */
    record IdentityProvider(String entityId, String displayName, List<String> names, List<Scope> scopes) {

        /** Whether {@code value} (a schacHomeOrganization or the part of an eppn after the @) is one of this provider's scopes. */
        boolean hasScope(String value) {
            return scopes.stream().anyMatch(s -> s.matches(value));
        }

        boolean matches(String query) {
            String q = query.trim().toLowerCase(Locale.ROOT);
            return q.isEmpty() || names.stream().anyMatch(n -> n.toLowerCase(Locale.ROOT).contains(q))
                    || entityId.toLowerCase(Locale.ROOT).contains(q);
        }
    }

    /** A {@code shibmd:Scope}: the organisation domains an identity provider may assert. */
    record Scope(String value, boolean regexp) {

        boolean matches(String candidate) {
            if (candidate == null) {
                return false;
            }
            if (!regexp) {
                return value.equalsIgnoreCase(candidate.trim());
            }
            try {
                return Pattern.compile(value, Pattern.CASE_INSENSITIVE).matcher(candidate.trim()).matches();
            } catch (PatternSyntaxException e) {
                return false;
            }
        }
    }

    private record Snapshot(OpenSaml4AssertingPartyMetadataRepository parties, Map<String, IdentityProvider> providers,
            List<IdentityProvider> listed, Instant validUntil) {}

    private final boolean enabled;
    private final String location;
    private final String certificateLocation;
    private final Duration refresh;
    private final ResourceLoader resources;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private volatile Snapshot snapshot;
    private ScheduledExecutorService reloader;

    FederationMetadata(
            @Value("${hestia.federation.enabled:false}") boolean enabled,
            @Value("${hestia.federation.metadata-location:}") String location,
            @Value("${hestia.federation.signing-certificate:}") String certificateLocation,
            @Value("${hestia.federation.refresh:6h}") Duration refresh,
            ResourceLoader resources) {
        this.enabled = enabled;
        this.location = location;
        this.certificateLocation = certificateLocation;
        this.refresh = refresh;
        this.resources = resources;
        if (!enabled) {
            return;
        }
        if (location.isBlank() || certificateLocation.isBlank()) {
            throw new IllegalStateException(
                    "hestia.federation.enabled needs hestia.federation.metadata-location and hestia.federation.signing-certificate");
        }
        OpenSamlInitializationService.initialize();
        reloader = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "federation-metadata");
            t.setDaemon(true);
            return t;
        });
        reload();
    }

    @PreDestroy
    void stop() {
        if (reloader != null) {
            reloader.shutdownNow();
        }
    }

    boolean enabled() {
        return enabled;
    }

    /** The providers to offer at login: sorted by name, without those that ask to be hidden. */
    List<IdentityProvider> identityProviders() {
        Snapshot s = current();
        return s == null ? List.of() : s.listed();
    }

    Optional<IdentityProvider> find(String entityId) {
        Snapshot s = current();
        return s == null ? Optional.empty() : Optional.ofNullable(s.providers().get(entityId));
    }

    Optional<AssertingPartyMetadata> assertingParty(String entityId) {
        Snapshot s = current();
        if (s == null || !s.providers().containsKey(entityId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(s.parties().findByEntityId(entityId));
    }

    /**
     * The loaded metadata while its {@code validUntil} lies ahead. Past it, a snapshot that could
     * not be refreshed is dropped: only the TUM login remains (fail closed), not stale keys.
     */
    private Snapshot current() {
        Snapshot s = snapshot;
        if (s != null && !Instant.now().isBefore(s.validUntil())) {
            synchronized (this) {
                if (snapshot == s) {
                    snapshot = null;
                    log.error("Federation metadata from {} expired at {} without a valid refresh; "
                            + "only the home login is offered until a reload succeeds", location, s.validUntil());
                }
            }
            return null;
        }
        return s;
    }

    private void reload() {
        Duration next = refresh;
        try {
            Snapshot loaded = load();
            snapshot = loaded;
            log.info("Federation metadata loaded from {}: {} identity providers, valid until {}", location,
                    loaded.providers().size(), loaded.validUntil());
        } catch (Exception e) {
            Snapshot kept = current();
            log.error("Federation metadata from {} rejected or unreachable; keeping {} identity providers{}",
                    location, kept == null ? 0 : kept.providers().size(),
                    kept == null ? "" : " until " + kept.validUntil(), e);
            if (kept == null) {
                next = RETRY;
            }
        }
        if (!reloader.isShutdown()) {
            reloader.schedule(this::reload, next.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private Snapshot load() throws Exception {
        Document document;
        try (InputStream in = new ByteArrayInputStream(fetch())) {
            document = XMLObjectProviderRegistrySupport.getParserPool().parse(in);
        }
        String validUntilText = document.getDocumentElement().getAttribute("validUntil");
        if (validUntilText.isBlank() || !Instant.parse(validUntilText).isAfter(Instant.now())) {
            throw new IllegalStateException("The federation metadata has expired or has no validUntil: " + validUntilText);
        }
        Instant validUntil = Instant.parse(validUntilText);
        DOMMetadataResolver resolver = new DOMMetadataResolver(document.getDocumentElement());
        resolver.setId("hestia-federation");
        resolver.setRequireValidMetadata(true);
        resolver.setFailFastInitialization(true);
        resolver.setIndexes(Set.of(new RoleMetadataIndex()));
        resolver.setMetadataFilter(filters());
        resolver.initialize();

        Map<String, IdentityProvider> providers = new LinkedHashMap<>();
        List<IdentityProvider> listed = new ArrayList<>();
        for (EntityDescriptor entity : resolver.resolve(new CriteriaSet(
                new EntityRoleCriterion(IDPSSODescriptor.DEFAULT_ELEMENT_NAME)))) {
            IDPSSODescriptor idp = entity.getIDPSSODescriptor(SAMLConstants.SAML20P_NS);
            if (idp == null || idp.getSingleSignOnServices().isEmpty()) {
                continue;
            }
            IdentityProvider provider = provider(entity, idp);
            providers.put(provider.entityId(), provider);
            if (!hidden(entity)) {
                listed.add(provider);
            }
        }
        if (providers.isEmpty()) {
            throw new IllegalStateException("The federation metadata names no SAML 2 identity provider");
        }
        Collator collator = Collator.getInstance(Locale.GERMAN);
        listed.sort(Comparator.comparing(IdentityProvider::displayName, collator));
        return new Snapshot(new OpenSaml4AssertingPartyMetadataRepository(resolver), Map.copyOf(providers),
                List.copyOf(listed), validUntil);
    }

    private MetadataFilter filters() throws Exception {
        List<Credential> trusted = List.of(new BasicX509Credential(certificate()));
        SignatureValidationFilter signature = new SignatureValidationFilter(new ExplicitKeySignatureTrustEngine(
                new CollectionCredentialResolver(trusted),
                DefaultSecurityConfigurationBootstrap.buildBasicInlineKeyInfoCredentialResolver()));
        signature.setRequireSignedRoot(true);
        RequiredValidUntilFilter validUntil = new RequiredValidUntilFilter();
        validUntil.setMaxValidityInterval(MAX_VALIDITY);
        MetadataFilterChain chain = new MetadataFilterChain();
        chain.setFilters(List.of(signature, validUntil));
        return chain;
    }

    private X509Certificate certificate() throws Exception {
        try (InputStream in = resources.getResource(certificateLocation).getInputStream()) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }

    private byte[] fetch() throws IOException, InterruptedException {
        if (location.startsWith("https://") || location.startsWith("http://")) {
            HttpResponse<InputStream> response = http.send(
                    HttpRequest.newBuilder(URI.create(location)).timeout(Duration.ofSeconds(60)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    throw new IOException("HTTP " + response.statusCode());
                }
                return readLimited(body);
            }
        }
        try (InputStream in = resources.getResource(location).getInputStream()) {
            return readLimited(in);
        }
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        byte[] bytes = in.readNBytes((int) MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) {
            throw new IOException("Federation metadata larger than " + MAX_BYTES + " bytes");
        }
        return bytes;
    }

    private static IdentityProvider provider(EntityDescriptor entity, IDPSSODescriptor idp) {
        Map<String, String> byLanguage = new LinkedHashMap<>();
        Extensions extensions = idp.getExtensions();
        List<Scope> scopes = new ArrayList<>();
        if (extensions != null) {
            for (XMLObject ui : extensions.getUnknownXMLObjects(UIInfo.DEFAULT_ELEMENT_NAME)) {
                if (ui instanceof UIInfo info) {
                    info.getDisplayNames().forEach(n -> byLanguage.putIfAbsent(lang(n.getXMLLang()), n.getValue()));
                }
            }
            for (XMLObject scope : extensions.getUnknownXMLObjects(SCOPE)) {
                scope(scope).ifPresent(scopes::add);
            }
        }
        Organization organization = entity.getOrganization();
        if (byLanguage.isEmpty() && organization != null) {
            organization.getDisplayNames().forEach(n -> byLanguage.putIfAbsent(lang(n.getXMLLang()), n.getValue()));
        }
        byLanguage.values().removeIf(v -> v == null || v.isBlank());
        String displayName = Optional.ofNullable(byLanguage.get("de"))
                .or(() -> Optional.ofNullable(byLanguage.get("en")))
                .or(() -> byLanguage.values().stream().findFirst())
                .orElse(entity.getEntityID()).trim();
        return new IdentityProvider(entity.getEntityID(), displayName, List.copyOf(byLanguage.values()),
                List.copyOf(scopes));
    }

    private static String lang(String lang) {
        return lang == null ? "" : lang.toLowerCase(Locale.ROOT);
    }

    private static Optional<Scope> scope(XMLObject scope) {
        if (!(scope instanceof XSAny any) || any.getTextContent() == null || any.getTextContent().isBlank()) {
            return Optional.empty();
        }
        String regexp = any.getUnknownAttributes().get(new QName("regexp"));
        return Optional.of(new Scope(any.getTextContent().trim(),
                "true".equalsIgnoreCase(regexp) || "1".equals(regexp)));
    }

    private static boolean hidden(EntityDescriptor entity) {
        Extensions extensions = entity.getExtensions();
        if (extensions == null) {
            return false;
        }
        for (XMLObject object : extensions.getUnknownXMLObjects(EntityAttributes.DEFAULT_ELEMENT_NAME)) {
            if (!(object instanceof EntityAttributes attributes)) {
                continue;
            }
            for (Attribute attribute : attributes.getAttributes()) {
                if (ENTITY_CATEGORY.equals(attribute.getName()) && attribute.getAttributeValues().stream()
                        .anyMatch(v -> HIDE_FROM_DISCOVERY.equals(text(v)))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String text(XMLObject value) {
        if (value instanceof XSString s) {
            return s.getValue();
        }
        if (value instanceof XSAny a) {
            return a.getTextContent();
        }
        return null;
    }
}
