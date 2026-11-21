package de.tum.cit.hestia.core.internal.security;

import static de.tum.cit.hestia.core.internal.security.FederationTestKit.ATTR_DISPLAY_NAME;
import static de.tum.cit.hestia.core.internal.security.FederationTestKit.ATTR_EPPN;
import static de.tum.cit.hestia.core.internal.security.FederationTestKit.ATTR_SCHAC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.tum.cit.hestia.TestDatabase;
import de.tum.cit.hestia.core.internal.identity.HestiaPrincipal;
import de.tum.cit.hestia.core.internal.security.FederationTestKit.Idp;
import de.tum.cit.hestia.core.internal.security.FederationTestKit.Party;
import jakarta.servlet.http.Cookie;
import java.io.ByteArrayOutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Logins through a federation with two simulated universities (each its own identity provider in a
 * signed federation aggregate) next to the configured TUM login: each university gets its own
 * organisation, neither sees the other's data, and neither identity provider can log into the other
 * university. The service provider stays the registered one.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FederatedLoginIT {

    static final String SP_ENTITY_ID = "http://localhost/saml2/service-provider-metadata/tum";
    static final String ACS = "http://localhost/login/saml2/sso/tum";
    static final String HOME = FederatedRegistrations.HOME;

    static final String RUN = UUID.randomUUID().toString().substring(0, 8);
    static final Party FEDERATION = Party.create("Test Federation Signer");
    static final Idp ALPHA = Idp.create("https://idp.alpha-" + RUN + ".test/idp", "Universität Alpha " + RUN,
            "alpha-" + RUN + ".test");
    static final Idp BETA = Idp.create("https://idp.beta-" + RUN + ".test/idp", "Beta University " + RUN,
            "beta-" + RUN + ".test");
    static final Idp TUM = Idp.create("https://idp.tum-" + RUN + ".test/idp", "Home University " + RUN,
            "tum-" + RUN + ".test");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        TestDatabase.register(registry);
        Path dir = Files.createTempDirectory("federation-it");
        Party sp = Party.create("Hestia Test SP");
        registry.add("SAML_PRIVATE_KEY_LOCATION", () -> uri(write(() -> sp.writePrivateKey(dir.resolve("sp.key")))));
        registry.add("SAML_CERTIFICATE_LOCATION", () -> uri(write(() -> sp.writeCertificate(dir.resolve("sp.crt")))));
        registry.add("SAML_IDP_ENTITY_ID", TUM::entityId);
        registry.add("SAML_IDP_METADATA_URI", () -> uri(write(() -> Files.writeString(dir.resolve("tum.xml"), TUM.metadata()))));
        registry.add("hestia.federation.enabled", () -> "true");
        registry.add("hestia.federation.metadata-location", () -> uri(write(() -> Files.writeString(dir.resolve("fed.xml"),
                FederationTestKit.aggregate(FEDERATION, Instant.now().plus(Duration.ofDays(7)), ALPHA, BETA, TUM)))));
        registry.add("hestia.federation.signing-certificate", () -> uri(write(() -> FEDERATION.writeCertificate(dir.resolve("fed.pem")))));
        registry.add("hestia.federation.home-label", () -> "Home Login");
        registry.add("hestia.security.platform-admins",
                () -> "root@" + TUM.scopes().get(0) + ",root@" + BETA.scopes().get(0));
    }

    interface IoSupplier {
        Path get() throws Exception;
    }

    private static Path write(IoSupplier writer) {
        try {
            return writer.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String uri(Path path) {
        return path.toUri().toString();
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    /** id and display name of the organisation, read like an operator would. */
    private Map<String, Object> organisation(String schacHomeOrganization) {
        return jdbc.queryForMap("select id, display_name from core.organisation where schac_home_organization = ?",
                schacHomeOrganization);
    }

    @Test
    void discoveryOffersTheHomeLoginFirstAndTheFederationSearchable() throws Exception {
        String page = mvc.perform(get("/saml2/discovery")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(page).contains("href=\"authenticate/tum\">Home Login</a>")
                .contains(ALPHA.name(), BETA.name())
                .contains("authenticate/" + FederatedRegistrations.registrationId(ALPHA.entityId()))
                // The home identity provider is the button, not a second list entry
                .doesNotContain(TUM.name());
        assertThat(page.indexOf("authenticate/tum")).isLessThan(page.indexOf(ALPHA.name()));

        String search = mvc.perform(get("/saml2/discovery").param("q", "beta univ")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(search).contains(BETA.name()).doesNotContain(ALPHA.name());

        String escaped = mvc.perform(get("/saml2/discovery").param("q", "<script>")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(escaped).doesNotContain("<script>").contains("&lt;script&gt;");
    }

    @Test
    void twoUniversitiesGetTheirOwnOrganisationAndSeparateData() throws Exception {
        MockHttpSession ada = login(ALPHA, "ada", ALPHA.scopes().get(0), ALPHA);
        MockHttpSession bob = login(BETA, "bob", BETA.scopes().get(0), BETA);

        HestiaPrincipal adaPrincipal = principal(ada);
        HestiaPrincipal bobPrincipal = principal(bob);
        assertThat(adaPrincipal.orgId()).isNotEqualTo(bobPrincipal.orgId());
        assertThat(adaPrincipal.externalId()).isEqualTo("ada@" + ALPHA.scopes().get(0));
        Map<String, Object> alpha = organisation(ALPHA.scopes().get(0));
        assertThat(alpha.get("id")).isEqualTo(adaPrincipal.orgId());
        assertThat(alpha.get("display_name")).isEqualTo(ALPHA.name());
        assertThat(organisation(BETA.scopes().get(0)).get("display_name")).isEqualTo(BETA.name());

        createCourse(ada, "Alpha course " + RUN);
        createCourse(bob, "Beta course " + RUN);
        assertThat(courses(ada)).contains("Alpha course " + RUN).doesNotContain("Beta course " + RUN);
        assertThat(courses(bob)).contains("Beta course " + RUN).doesNotContain("Alpha course " + RUN);

        // A second login of the same person lands in the same organisation and user
        assertThat(principal(login(ALPHA, "ada", ALPHA.scopes().get(0), ALPHA)).userId()).isEqualTo(adaPrincipal.userId());
    }

    @Test
    void aUniversityCannotLogIntoAnotherUniversity() throws Exception {
        String alphaScope = ALPHA.scopes().get(0);
        // Beta's identity provider claims to be Alpha (attribute, then principal name scope)
        assertRejected(BETA, Map.of(ATTR_SCHAC, alphaScope, ATTR_EPPN, "eve@" + BETA.scopes().get(0)), BETA.party());
        assertRejected(BETA, Map.of(ATTR_EPPN, "eve@" + alphaScope), BETA.party());
        // ... or into the home university
        assertRejected(BETA, Map.of(ATTR_SCHAC, TUM.scopes().get(0), ATTR_EPPN, "eve@" + BETA.scopes().get(0)), BETA.party());
        // A response for Alpha that Alpha did not sign
        assertRejected(ALPHA, Map.of(ATTR_SCHAC, alphaScope, ATTR_EPPN, "eve@" + alphaScope), BETA.party());
    }

    @Test
    void aResponseToARequestTheServerDidNotSendIsRejected() throws Exception {
        String scope = ALPHA.scopes().get(0);
        String response = ALPHA.response(SP_ENTITY_ID, ACS, "ARQ-unknown",
                Map.of(ATTR_SCHAC, scope, ATTR_EPPN, "eve@" + scope), ALPHA.party());
        mvc.perform(post("/login/saml2/sso/tum").param("SAMLResponse", encode(response)).param("RelayState", "forged"))
                .andExpect(status().isForbidden());
    }

    @Test
    void theHomeLoginWorksAsBefore() throws Exception {
        String scope = TUM.scopes().get(0);
        MvcResult done = answer(HOME, TUM, Map.of(ATTR_SCHAC, scope, ATTR_EPPN, "carl@" + scope), TUM.party(), true);
        assertThat(done.getResponse().getStatus()).isEqualTo(302);
        HestiaPrincipal carl = principal((MockHttpSession) done.getRequest().getSession(false));
        assertThat(carl.externalId()).isEqualTo("carl@" + scope);
        assertThat(carl.admin()).isFalse();
    }

    @Test
    void unsolicitedResponsesAreRejectedForTheHomeLoginAndTheFederation() throws Exception {
        String tum = TUM.scopes().get(0);
        String home = TUM.response(SP_ENTITY_ID, ACS, null, Map.of(ATTR_SCHAC, tum, ATTR_EPPN, "carl@" + tum), TUM.party());
        mvc.perform(post("/login/saml2/sso/tum").param("SAMLResponse", encode(home))).andExpect(status().isForbidden());

        String alpha = ALPHA.scopes().get(0);
        String federated = ALPHA.response(SP_ENTITY_ID, ACS, null,
                Map.of(ATTR_SCHAC, alpha, ATTR_EPPN, "ada@" + alpha), ALPHA.party());
        mvc.perform(post("/login/saml2/sso/tum").param("SAMLResponse", encode(federated))).andExpect(status().isForbidden());
    }

    @Test
    void aResponseCannotBePostedFromAnotherBrowser() throws Exception {
        // Login CSRF: the attacker's own valid response and RelayState, posted from the victim's browser
        String alpha = ALPHA.scopes().get(0);
        MvcResult fromVictim = answer(FederatedRegistrations.registrationId(ALPHA.entityId()), ALPHA,
                Map.of(ATTR_SCHAC, alpha, ATTR_EPPN, "mallory@" + alpha), ALPHA.party(), false);
        assertThat(fromVictim.getResponse().getStatus()).isEqualTo(403);

        String tum = TUM.scopes().get(0);
        MvcResult home = answer(HOME, TUM, Map.of(ATTR_SCHAC, tum, ATTR_EPPN, "mallory@" + tum), TUM.party(), false);
        assertThat(home.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void onlyTheHomeLoginMakesAPlatformAdmin() throws Exception {
        String tum = TUM.scopes().get(0);
        String beta = BETA.scopes().get(0);
        MvcResult root = answer(HOME, TUM, Map.of(ATTR_SCHAC, tum, ATTR_EPPN, "root@" + tum), TUM.party(), true);
        assertThat(principal((MockHttpSession) root.getRequest().getSession(false)).admin()).isTrue();

        // Beta names a TUM admin in the NameID and sends no eduPersonPrincipalName
        MvcResult nameId = answer(FederatedRegistrations.registrationId(BETA.entityId()), BETA,
                Map.of(ATTR_SCHAC, beta), BETA.party(), true, "root@" + tum);
        assertThat(nameId.getResponse().getStatus()).isEqualTo(403);

        // A listed id that logs in through the federation is no admin either
        HestiaPrincipal betaRoot = principal(login(BETA, "root", beta, BETA));
        assertThat(betaRoot.externalId()).isEqualTo("root@" + beta);
        assertThat(betaRoot.admin()).isFalse();
    }

    @Test
    void theServiceProviderStaysTheRegisteredOne() throws Exception {
        String metadata = mvc.perform(get("/saml2/service-provider-metadata/tum").header("X-Forwarded-Prefix", "/workshopper"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(metadata).contains("entityID=\"http://localhost/workshopper/saml2/service-provider-metadata/tum\"")
                .contains("Location=\"http://localhost/workshopper/login/saml2/sso/tum\"");

        // A federation login under a client prefix asks with the same service provider and ACS
        String request = authnRequest(mvc.perform(get("/saml2/authenticate/" + FederatedRegistrations.registrationId(BETA.entityId()))
                .header("X-Forwarded-Prefix", "/workshopper")).andExpect(status().is3xxRedirection()).andReturn());
        assertThat(request).contains(">http://localhost/workshopper/saml2/service-provider-metadata/tum</")
                .contains("AssertionConsumerServiceURL=\"http://localhost/workshopper/login/saml2/sso/tum\"");
    }

    @Test
    void withoutFederationTheDiscoveryForwardsToTheHomeLogin() {
        DiscoveryPage page = new DiscoveryPage(new FederationMetadata(false, "", "", Duration.ofHours(1),
                new org.springframework.core.io.DefaultResourceLoader()), null, "TUM");
        var request = new org.springframework.mock.web.MockHttpServletRequest("GET", DiscoveryPage.PATH);
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        try {
            page.doFilter(request, response, (req, res) -> { throw new AssertionError("not handled"); });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost/saml2/authenticate/tum");
    }

    // --- helpers

    /** Starts the login at {@code idp}, answers as {@code idp} (signed by {@code signer}) without the session, like the cross-site POST. */
    private MockHttpSession login(Idp idp, String user, String scope, Idp signer) throws Exception {
        MvcResult done = answer(idp, Map.of(ATTR_SCHAC, scope, ATTR_EPPN, user + "@" + scope, ATTR_DISPLAY_NAME, user),
                signer.party());
        assertThat(done.getResponse().getStatus()).as("login of %s", user).isEqualTo(302);
        return (MockHttpSession) done.getRequest().getSession(false);
    }

    private void assertRejected(Idp idp, Map<String, String> attributes, Party signer) throws Exception {
        MvcResult done = answer(idp, attributes, signer);
        assertThat(done.getResponse().getStatus()).isEqualTo(403);
        var session = done.getRequest().getSession(false);
        assertThat(session == null || session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) == null).isTrue();
    }

    private MvcResult answer(Idp idp, Map<String, String> attributes, Party signer) throws Exception {
        return answer(FederatedRegistrations.registrationId(idp.entityId()), idp, attributes, signer, true);
    }

    private MvcResult answer(String registrationId, Idp idp, Map<String, String> attributes, Party signer,
            boolean sameBrowser) throws Exception {
        return answer(registrationId, idp, attributes, signer, sameBrowser, "_n" + UUID.randomUUID());
    }

    /**
     * Starts the login at {@code registrationId} and posts {@code idp}'s answer to the ACS without
     * the session (the cross-site POST), with the nonce cookie of the start if {@code sameBrowser}.
     */
    private MvcResult answer(String registrationId, Idp idp, Map<String, String> attributes, Party signer,
            boolean sameBrowser, String nameId) throws Exception {
        MvcResult start = mvc.perform(get("/saml2/authenticate/" + registrationId))
                .andExpect(status().is3xxRedirection()).andReturn();
        assertThat(start.getResponse().getRedirectedUrl()).startsWith(idp.ssoUrl() + "?SAMLRequest=");
        Matcher id = Pattern.compile(" ID=\"([^\"]+)\"").matcher(authnRequest(start));
        assertThat(id.find()).isTrue();
        Matcher relayState = Pattern.compile("RelayState=([^&]+)").matcher(start.getResponse().getRedirectedUrl());
        assertThat(relayState.find()).isTrue();
        Cookie nonce = start.getResponse().getCookie(RelayStateAuthenticationRequests.COOKIE);
        assertThat(nonce).isNotNull();
        String response = idp.response(SP_ENTITY_ID, ACS, id.group(1), attributes, signer, nameId);
        var post = post("/login/saml2/sso/tum").param("SAMLResponse", encode(response))
                .param("RelayState", URLDecoder.decode(relayState.group(1), StandardCharsets.UTF_8));
        if (sameBrowser) {
            post.cookie(new Cookie(nonce.getName(), nonce.getValue()));
        }
        return mvc.perform(post).andReturn();
    }

    private static String authnRequest(MvcResult redirect) throws Exception {
        String url = redirect.getResponse().getRedirectedUrl();
        Matcher m = Pattern.compile("SAMLRequest=([^&]+)").matcher(url);
        assertThat(m.find()).isTrue();
        byte[] deflated = Base64.getDecoder().decode(URLDecoder.decode(m.group(1), StandardCharsets.UTF_8));
        Inflater inflater = new Inflater(true);
        inflater.setInput(deflated);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        while (!inflater.finished()) {
            int n = inflater.inflate(buffer);
            if (n == 0 && inflater.needsInput()) {
                break;
            }
            out.write(buffer, 0, n);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String encode(String xml) {
        return Base64.getEncoder().encodeToString(xml.getBytes(StandardCharsets.UTF_8));
    }

    private static HestiaPrincipal principal(MockHttpSession session) {
        assertThat(session).isNotNull();
        SecurityContext context = (SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(context).isNotNull();
        return (HestiaPrincipal) context.getAuthentication().getPrincipal();
    }

    private void createCourse(MockHttpSession session, String name) throws Exception {
        mvc.perform(post("/learninggoalhub/api/courses").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().is2xxSuccessful());
    }

    private String courses(MockHttpSession session) throws Exception {
        return mvc.perform(get("/learninggoalhub/api/courses").session(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
