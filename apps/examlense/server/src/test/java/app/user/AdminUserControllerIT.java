package app.user;

import app.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Admin surface: the user roster, promotion, and revocation. */
@AutoConfigureMockMvc
class AdminUserControllerIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;

    private static String admin() {
        return "Bearer " + TEST_ADMIN_TOKEN;
    }

    @Test
    void rosterListsUsersWithTheirActiveTokenState() throws Exception {
        TestUser enrolled = createUser("roster-" + UUID.randomUUID());

        mvc.perform(get("/api/admin/users").header("Authorization", admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == '" + enrolled.id() + "')].has_active_token")
                .value(org.hamcrest.Matchers.hasItem(true)));
    }

    @Test
    void promotingAUserGrantsThemAdminImmediately() throws Exception {
        TestUser plain = createUser("promote-" + UUID.randomUUID());

        mvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + plain.token()))
            .andExpect(status().isForbidden());

        mvc.perform(patch("/api/admin/users/" + plain.id())
                .header("Authorization", admin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"is_admin\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.is_admin").value(true));

        // No cache TTL to wait out — the controller invalidates the principal cache.
        mvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + plain.token()))
            .andExpect(status().isOk());
    }

    @Test
    void demotingRemovesAdmin() throws Exception {
        TestUser other = createAdmin("demote-" + UUID.randomUUID());

        mvc.perform(patch("/api/admin/users/" + other.id())
                .header("Authorization", admin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"is_admin\":false}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.is_admin").value(false));

        mvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + other.token()))
            .andExpect(status().isForbidden());
    }

    /**
     * Guards against the deployment ending up with no administrator and no way back
     * in short of database access.
     */
    @Test
    void anAdminCannotChangeTheirOwnFlag() throws Exception {
        TestUser self = createAdmin("self-" + UUID.randomUUID());

        mvc.perform(patch("/api/admin/users/" + self.id())
                .header("Authorization", "Bearer " + self.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"is_admin\":false}"))
            .andExpect(status().isConflict());

        mvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + self.token()))
            .andExpect(status().isOk());
    }

    @Test
    void promotingAnUnknownUserIsNotFound() throws Exception {
        mvc.perform(patch("/api/admin/users/" + UUID.randomUUID())
                .header("Authorization", admin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"is_admin\":true}"))
            .andExpect(status().isNotFound());
    }

    @Test
    void aPlainUserCannotReachAnyAdminRoute() throws Exception {
        TestUser plain = createUser("outsider-" + UUID.randomUUID());
        String bearer = "Bearer " + plain.token();

        mvc.perform(get("/api/admin/users").header("Authorization", bearer))
            .andExpect(status().isForbidden());
        mvc.perform(patch("/api/admin/users/" + plain.id()).header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"is_admin\":true}"))
            .andExpect(status().isForbidden());
    }
}
