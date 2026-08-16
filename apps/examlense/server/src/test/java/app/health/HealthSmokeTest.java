package app.health;

import app.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Is the service actually up and answering?" The liveness probe must respond
 * without a token (deploy health checks are unauthenticated), and the
 * token-gated {@code /api/me} must resolve the principal so we know auth works
 * end-to-end through the real filter chain.
 */
@AutoConfigureMockMvc
class HealthSmokeTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void healthzIsPublicAndReportsOk() throws Exception {
        mvc.perform(get("/api/healthz"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void meReturnsThePrincipalWithAValidToken() throws Exception {
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + TEST_TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value("00000000-0000-0000-0000-000000000001"));
    }

    @Test
    void meIsRejectedWithoutAToken() throws Exception {
        mvc.perform(get("/api/me"))
            .andExpect(status().isUnauthorized());
    }
}
