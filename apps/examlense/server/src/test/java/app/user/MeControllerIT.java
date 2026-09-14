package app.user;

import app.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/me} is what the client trusts to decide whether to offer the
 * admin area at all, so its {@code is_admin} has to match what the server will
 * actually authorize. The two can drift: the bootstrap admin token grants admin
 * without setting {@code users.is_admin} on any row.
 */
@AutoConfigureMockMvc
class MeControllerIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;

    @Test
    void reportsTheEnrolledUsersIdentityAndQuota() throws Exception {
        TestUser user = createUser("me-" + UUID.randomUUID());

        mvc.perform(get("/api/me").header("Authorization", "Bearer " + user.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(user.id().toString()))
            .andExpect(jsonPath("$.is_admin").value(false))
            .andExpect(jsonPath("$.quota.parse_limit").isNumber());
    }

    @Test
    void reportsAdminForAPromotedAccount() throws Exception {
        TestUser admin = createAdmin("me-admin-" + UUID.randomUUID());

        mvc.perform(get("/api/me").header("Authorization", "Bearer " + admin.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.is_admin").value(true));
    }

    /**
     * The shared token is public, so it must not advertise admin — otherwise the
     * client offers an admin area that every request into it then 403s.
     */
    @Test
    void theSharedTokenDoesNotReportAdmin() throws Exception {
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + TEST_TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("00000000-0000-0000-0000-000000000001"))
            .andExpect(jsonPath("$.is_admin").value(false));
    }

    /**
     * The mirror case: the bootstrap token grants admin through the filter without
     * any row carrying the flag, so reading the column here would hide an admin
     * area the caller is entitled to.
     */
    @Test
    void theAdminBootstrapTokenReportsAdminEvenThoughNoRowCarriesTheFlag() throws Exception {
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + TEST_ADMIN_TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("00000000-0000-0000-0000-000000000001"))
            .andExpect(jsonPath("$.is_admin").value(true));
    }

    @Test
    void requiresAToken() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }
}
