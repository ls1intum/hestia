package de.tum.cit.hestia.assessment.config;

import de.tum.cit.hestia.assessment.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The "front door" wiring: which routes are public and which require the token.
 * A regression here (e.g. accidentally opening a protected route, or breaking
 * CORS preflight so the browser blocks every call) is exactly the kind of "big
 * break" this tier is meant to catch before deploy.
 */
@AutoConfigureMockMvc
class SecurityRulesSmokeTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void protectedEndpointRejectsMissingToken() throws Exception {
        mvc.perform(get("/examlense/api/exams"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpointRejectsWrongToken() throws Exception {
        mvc.perform(get("/examlense/api/exams").header("Authorization", "Bearer nope"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpointAllowsValidToken() throws Exception {
        mvc.perform(get("/examlense/api/exams").header("Authorization", "Bearer " + TEST_TOKEN))
            .andExpect(status().isOk());
    }

    @Test
    void corsPreflightIsAllowedWithoutTokenAndEchoesTheOrigin() throws Exception {
        mvc.perform(options("/examlense/api/exams")
                .header("Origin", "http://localhost:8080")
                .header("Access-Control-Request-Method", "GET"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8080"));
    }

    @Test
    void apiDocsAreNotExposedByDefault() throws Exception {
        // app.docs.enabled defaults to false: the assessment group is not registered, and the
        // paths of the module are stripped from every document the server still serves for
        // the other modules.
        mvc.perform(get("/v3/api-docs/assessment"))
            .andExpect(status().isNotFound());

        MvcResult result = mvc.perform(get("/v3/api-docs")).andReturn();
        if (result.getResponse().getStatus() == 200) {
            assertThat(result.getResponse().getContentAsString()).doesNotContain("/examlense/");
        }
    }

    /**
     * Cross-user surfaces need the role, not just a principal. Everything else is
     * filtered to the caller's own rows inside the controllers, but these two
     * aggregate or administer across users, so authentication alone is not enough.
     */
    @Test
    void parseMetricsRequiresTheAdminRole() throws Exception {
        TestUser plain = createUser("smoke-plain-" + UUID.randomUUID());

        mvc.perform(get("/examlense/api/parse-metrics").header("Authorization", "Bearer " + plain.token()))
            .andExpect(status().isForbidden());
    }

    @Test
    void parseMetricsIsAllowedForAnAdmin() throws Exception {
        TestUser admin = createAdmin("smoke-admin-" + UUID.randomUUID());

        mvc.perform(get("/examlense/api/parse-metrics").header("Authorization", "Bearer " + admin.token()))
            .andExpect(status().isOk());
    }

    /**
     * The shared token is committed to a public repository, so it must authenticate
     * as a plain user and nothing more. If this ever regresses, anyone who reads the
     * repo can promote themselves and revoke other people's access.
     */
    @Test
    void theSharedTokenIsNotAdmin() throws Exception {
        mvc.perform(get("/examlense/api/exams").header("Authorization", "Bearer " + TEST_TOKEN))
            .andExpect(status().isOk());

        mvc.perform(get("/examlense/api/admin/users").header("Authorization", "Bearer " + TEST_TOKEN))
            .andExpect(status().isForbidden());
        mvc.perform(get("/examlense/api/parse-metrics").header("Authorization", "Bearer " + TEST_TOKEN))
            .andExpect(status().isForbidden());
    }

    @Test
    void theSeparateAdminBootstrapTokenIsAdmin() throws Exception {
        mvc.perform(get("/examlense/api/admin/users").header("Authorization", "Bearer " + TEST_ADMIN_TOKEN))
            .andExpect(status().isOk());
    }

    @Test
    void adminRoutesRequireTheAdminRole() throws Exception {
        TestUser plain = createUser("smoke-plain2-" + UUID.randomUUID());

        mvc.perform(get("/examlense/api/admin/users").header("Authorization", "Bearer " + plain.token()))
            .andExpect(status().isForbidden());
    }

    /** How a first-time visitor gets their first credential, so it cannot need one. */
    @Test
    void registrationIsPublicSoANewcomerCanGetIn() throws Exception {
        mvc.perform(post("/examlense/api/auth/register"))
            .andExpect(status().isOk());
    }

    /**
     * The SAML namespace must be served by the SAML chain, not the token-gated API
     * chain — {@code saml2Login} needs a session, which is why it has a chain of
     * its own. A redirect (rather than the API chain's 401 JSON envelope) is the
     * proof: it means the SAML entry point handled the request.
     *
     * <p>Not asserted here: that the metadata endpoint returns actual XML.
     * {@link AbstractIntegrationTest} mocks {@code RelyingPartyRegistrationRepository},
     * so no registration named {@code tum} exists in tests and
     * {@code Saml2MetadataFilter} answers 401 for the unknown id. Verify the real
     * metadata against a running instance instead.
     */
    @Test
    void samlNamespaceIsHandledByTheSamlChainNotTheApiChain() throws Exception {
        mvc.perform(get("/saml2/some-saml-route").header("X-Forwarded-Prefix", "/examlense"))
            .andExpect(status().is3xxRedirection());
    }

    @Test
    void samlMetadataPathIsNotTokenGated() throws Exception {
        // permitAll in the SAML chain, so it reaches the metadata filter rather than
        // being turned away by the API chain's bearer-token rules.
        mvc.perform(get("/saml2/service-provider-metadata/").header("X-Forwarded-Prefix", "/examlense"))
            .andExpect(status().isNotFound());
    }

    @Test
    void fileContentRouteIsPublicButRejectsAnUnsignedRequest() throws Exception {
        // permitAll at the security layer, so no 401; the HMAC check then rejects
        // a request with no/invalid signature (403), not an auth failure.
        mvc.perform(get("/examlense/api/files/exam-pdfs/whatever.pdf").param("exp", "1").param("sig", "bad"))
            .andExpect(status().isForbidden());
    }
}
