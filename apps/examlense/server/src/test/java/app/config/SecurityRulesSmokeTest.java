package app.config;

import app.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
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
        mvc.perform(get("/api/exams"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpointRejectsWrongToken() throws Exception {
        mvc.perform(get("/api/exams").header("Authorization", "Bearer nope"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpointAllowsValidToken() throws Exception {
        mvc.perform(get("/api/exams").header("Authorization", "Bearer " + TEST_TOKEN))
            .andExpect(status().isOk());
    }

    @Test
    void corsPreflightIsAllowedWithoutTokenAndEchoesTheOrigin() throws Exception {
        mvc.perform(options("/api/exams")
                .header("Origin", "http://localhost:8080")
                .header("Access-Control-Request-Method", "GET"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8080"));
    }

    @Test
    void fileContentRouteIsPublicButRejectsAnUnsignedRequest() throws Exception {
        // permitAll at the security layer, so no 401; the HMAC check then rejects
        // a request with no/invalid signature (403), not an auth failure.
        mvc.perform(get("/api/files/exam-pdfs/whatever.pdf").param("exp", "1").param("sig", "bad"))
            .andExpect(status().isForbidden());
    }
}
