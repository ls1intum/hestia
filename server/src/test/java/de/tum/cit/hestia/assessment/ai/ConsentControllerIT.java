package de.tum.cit.hestia.assessment.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.tum.cit.hestia.assessment.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** {@code /api/ai-consent}: notice and state, grant and revoke (each logged), login required. */
@AutoConfigureMockMvc
class ConsentControllerIT extends AbstractIntegrationTest {

    private static final String PATH = "/examlense/api/ai-consent";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    /** Entries of the append-only consent log for the user (the test's organisation is bound). */
    private int logEntries(String user) {
        return jdbc.queryForObject("select count(*) from ai.consent_event where user_id = ?::uuid",
            Integer.class, userId(user).toString());
    }

    @Test
    void startsUndecidedAndShowsTheNotice() throws Exception {
        mvc.perform(get(PATH).with(as("c-new")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.granted").value(false))
            .andExpect(jsonPath("$.last_action").doesNotExist())
            .andExpect(jsonPath("$.notice.version").isNotEmpty())
            .andExpect(jsonPath("$.notice.text").value(org.hamcrest.Matchers.containsString("Einwilligung")));
        assertThat(logEntries("c-new")).isZero();
    }

    @Test
    void grantAndRevokeEachAddALogEntryAndStatusReflectsTheLatest() throws Exception {
        mvc.perform(post(PATH).with(as("c-user")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.granted").value(true))
            .andExpect(jsonPath("$.last_action").value("GRANTED"));
        assertThat(logEntries("c-user")).isEqualTo(1);

        mvc.perform(delete(PATH).with(as("c-user")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.granted").value(false))
            .andExpect(jsonPath("$.last_action").value("REVOKED"));
        assertThat(logEntries("c-user")).isEqualTo(2);

        mvc.perform(get(PATH).with(as("c-user")))
            .andExpect(jsonPath("$.granted").value(false))
            .andExpect(jsonPath("$.last_action").value("REVOKED"));

        mvc.perform(post(PATH).with(as("c-user")))
            .andExpect(jsonPath("$.last_action").value("GRANTED"));
        assertThat(logEntries("c-user")).isEqualTo(3);
    }

    @Test
    void consentOfOneUserDoesNotCountForAnother() throws Exception {
        mvc.perform(post(PATH).with(as("c-a"))).andExpect(status().isOk());

        mvc.perform(get(PATH).with(as("c-b"))).andExpect(jsonPath("$.granted").value(false));
    }

    @Test
    void requiresALogin() throws Exception {
        // No authentication left on this thread by an earlier test may stand in for a login.
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        org.springframework.security.test.context.TestSecurityContextHolder.clearContext();
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(delete(PATH).with(csrf())).andExpect(status().isUnauthorized());
    }
}
