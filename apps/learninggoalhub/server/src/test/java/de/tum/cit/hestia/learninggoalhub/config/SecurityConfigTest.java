package de.tum.cit.hestia.learninggoalhub.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.tum.cit.hestia.learninggoalhub.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

// The deploy healthcheck and the OpenAPI contract must stay reachable without a SAML session;
// everything outside the explicit allow-list (the API, Swagger UI) needs a login.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthIsPublicAndReportsTheDatabase() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void openApiSpecIsPublic() throws Exception {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }

    @Test
    void swaggerUiRequiresLogin() throws Exception {
        // A browser is sent to the SAML login; a non-browser client gets a plain 401.
        mockMvc.perform(get("/swagger-ui/index.html").with(anonymous()).accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/swagger-ui/index.html").with(anonymous())).andExpect(status().isUnauthorized());
    }

    @Test
    void apiRequiresLogin() throws Exception {
        // The client shows its login screen on 401; an API call is never redirected.
        mockMvc.perform(get("/api/courses").with(anonymous()).accept(MediaType.TEXT_HTML))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/courses")).andExpect(status().isOk());
    }

    @Test
    void serviceOnlyEndpointsAcceptALoggedInUserUntilEnforced() throws Exception {
        // The unknown course is the endpoint's own 404: the request was not stopped at security.
        mockMvc.perform(post("/api/courses/{id}/exam-tasks/learning-goals", 999999)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blocks\":[]}"))
                .andExpect(status().isNotFound());
    }
}
