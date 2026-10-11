package app.user;

import app.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The off switch. Turning registration off has to freeze the instance to existing
 * token holders without breaking them — that is the escape hatch if the app ever
 * gets wider exposure than the VPN.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.auth.open-registration=false")
class ClosedRegistrationIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;

    @Test
    void newAccountsAreRefused() throws Exception {
        mvc.perform(post("/api/auth/register")).andExpect(status().isForbidden());
    }

    @Test
    void existingTokenHoldersAreUnaffected() throws Exception {
        TestUser existing = createUser("closed-reg-user");

        mvc.perform(get("/api/exams").header("Authorization", "Bearer " + existing.token()))
            .andExpect(status().isOk());
    }
}
