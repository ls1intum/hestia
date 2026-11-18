package de.tum.cit.hestia.assessment.config;

import de.tum.cit.hestia.assessment.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The assessment module keeps its own SAML chain: for the ExamLense client (context path
 * {@code /examlense}, from nginx's X-Forwarded-Prefix) the login ends in a redirect back to the
 * client with a freshly minted session token in the URL fragment. Other clients must not get one.
 * Runs with the {@code local} profile, whose development login stands in for the identity provider.
 */
@ActiveProfiles("local")
@AutoConfigureMockMvc
class AssessmentSamlChainIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void loginForTheExamLensClientRedirectsBackWithAToken() throws Exception {
        MvcResult result = mvc.perform(get("/saml2/authenticate/tum").header("X-Forwarded-Prefix", "/examlense"))
            .andExpect(status().is3xxRedirection())
            .andReturn();

        assertThat(result.getResponse().getRedirectedUrl()).contains("/examlense/#token=");
    }

    @Test
    void loginWithoutAPrefixIsNotHandledByTheAssessmentChain() throws Exception {
        MvcResult result = mvc.perform(get("/saml2/authenticate/tum")).andReturn();

        assertThat(String.valueOf(result.getResponse().getRedirectedUrl())).doesNotContain("#token=");
    }

    @Test
    void loginForAnotherClientIsNotHandledByTheAssessmentChain() throws Exception {
        MvcResult result = mvc.perform(get("/saml2/authenticate/tum").header("X-Forwarded-Prefix", "/workshopper"))
            .andReturn();

        assertThat(String.valueOf(result.getResponse().getRedirectedUrl())).doesNotContain("#token=");
    }
}
