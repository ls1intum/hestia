package de.tum.cit.hestia.assessment.user;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.tum.cit.hestia.assessment.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** {@code GET /api/me} answers from the session login: id, name, platform-admin flag, quota. */
@AutoConfigureMockMvc
class MeControllerIT extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void platformAdmin(DynamicPropertyRegistry registry) {
        registry.add("hestia.security.platform-admins", () -> "me-admin");
    }

    @Autowired MockMvc mvc;

    @Test
    void reportsTheLoggedInUsersIdentityAndQuota() throws Exception {
        mvc.perform(get("/examlense/api/me").with(as("me-user")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(userId("me-user").toString()))
            .andExpect(jsonPath("$.display_name").value("me-user"))
            .andExpect(jsonPath("$.is_admin").value(false))
            .andExpect(jsonPath("$.quota.parse_limit").isNumber());
    }

    @Test
    void reportsAdminForAPlatformAdmin() throws Exception {
        mvc.perform(get("/examlense/api/me").with(as("me-admin")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.is_admin").value(true));
    }

    @Test
    void parseMetricsNeedAnAdmin() throws Exception {
        mvc.perform(get("/examlense/api/parse-metrics").with(as("me-user"))).andExpect(status().isForbidden());
        mvc.perform(get("/examlense/api/parse-metrics").with(as("me-admin"))).andExpect(status().isOk());
    }

    @Test
    void requiresALogin() throws Exception {
        mvc.perform(get("/examlense/api/me")).andExpect(status().isUnauthorized());
    }
}
