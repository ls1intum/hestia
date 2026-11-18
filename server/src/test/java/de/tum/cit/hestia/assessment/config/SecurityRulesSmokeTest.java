package de.tum.cit.hestia.assessment.config;

import de.tum.cit.hestia.assessment.AbstractIntegrationTest;
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
 * The "front door" wiring: which routes are public and which require the session login.
 * A regression here (e.g. accidentally opening a protected route, or breaking
 * CORS preflight so the browser blocks every call) is exactly the kind of "big
 * break" this tier is meant to catch before deploy.
 */
@AutoConfigureMockMvc
class SecurityRulesSmokeTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void protectedEndpointRejectsMissingLogin() throws Exception {
        mvc.perform(get("/examlense/api/exams"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpointAllowsALoggedInUser() throws Exception {
        mvc.perform(get("/examlense/api/exams").with(as("alice")))
            .andExpect(status().isOk());
    }

    @Test
    void corsPreflightIsAllowedWithoutLoginAndEchoesTheOrigin() throws Exception {
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

    /** Cross-user aggregates need the platform-admin role, not just a login. */
    @Test
    void parseMetricsRequiresTheAdminRole() throws Exception {
        mvc.perform(get("/examlense/api/parse-metrics").with(as("alice")))
            .andExpect(status().isForbidden());
    }

    @Test
    void theRemovedTokenEndpointsAreGone() throws Exception {
        mvc.perform(post("/examlense/api/auth/register").with(as("alice")))
            .andExpect(status().isNotFound());
        mvc.perform(get("/examlense/api/admin/users").with(as("alice")))
            .andExpect(status().isForbidden());
    }

    @Test
    void fileContentRouteNeedsALoginAndRejectsAnUnsignedRequest() throws Exception {
        mvc.perform(get("/examlense/api/files/exam-pdfs/whatever.pdf").param("exp", "1").param("sig", "bad"))
            .andExpect(status().isUnauthorized());
        // Logged in, but a key outside the caller's organisation: not found, whatever the signature.
        mvc.perform(get("/examlense/api/files/exam-pdfs/whatever.pdf").param("exp", "1").param("sig", "bad")
                .with(as("alice")))
            .andExpect(status().isNotFound());
        mvc.perform(get("/examlense/api/files/exam-pdfs/" + orgId() + "/whatever.pdf")
                .param("exp", "1").param("sig", "bad").with(as("alice")))
            .andExpect(status().isForbidden());
    }
}
