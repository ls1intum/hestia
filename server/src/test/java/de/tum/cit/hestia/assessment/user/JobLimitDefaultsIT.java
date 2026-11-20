package de.tum.cit.hestia.assessment.user;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.tum.cit.hestia.assessment.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** Without configuration a user may start 10 parses and 30 solves per day; nothing is used yet. */
@AutoConfigureMockMvc
class JobLimitDefaultsIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;

    @Test
    void defaultsAreTenParsesAndThirtySolvesPerDay() throws Exception {
        mvc.perform(get("/examlense/api/me").with(as("defaults-user")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.quota.parse_limit").value(10))
            .andExpect(jsonPath("$.quota.parse_remaining").value(10))
            .andExpect(jsonPath("$.quota.parse_used").value(0))
            .andExpect(jsonPath("$.quota.solve_limit").value(30))
            .andExpect(jsonPath("$.quota.solve_remaining").value(30))
            .andExpect(jsonPath("$.quota.solve_used").value(0));
    }
}
