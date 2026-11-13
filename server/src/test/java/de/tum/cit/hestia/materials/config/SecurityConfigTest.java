package de.tum.cit.hestia.materials.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.tum.cit.hestia.materials.MaterialsTestBase;
import de.tum.cit.hestia.materials.MaterialsTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

// The deploy healthcheck and the OpenAPI contract stay reachable without a login; the API needs one.
@Import(MaterialsTestConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigTest extends MaterialsTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthIsPublicAndReportsTheDatabase() throws Exception {
        mockMvc.perform(get("/actuator/health").with(anonymous()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void openApiSpecIsPublic() throws Exception {
        mockMvc.perform(get("/v3/api-docs/materials").with(anonymous())).andExpect(status().isOk());
    }

    @Test
    void apiRequiresLogin() throws Exception {
        // The client shows its login screen on 401; an API call is never redirected.
        mockMvc.perform(get("/learninggoalhub/api/courses").with(anonymous()).accept(MediaType.TEXT_HTML))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/learninggoalhub/api/courses").with(anonymous()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/learninggoalhub/api/courses")).andExpect(status().isOk());
    }

    @Test
    void formerServiceOnlyEndpointsNeedJustALogin() throws Exception {
        mockMvc.perform(post("/learninggoalhub/api/courses/{id}/exam-tasks/learning-goals", 999999)
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blocks\":[]}"))
                .andExpect(status().isUnauthorized());
        // The unknown course is the endpoint's own 404: the request was not stopped at security.
        mockMvc.perform(post("/learninggoalhub/api/courses/{id}/exam-tasks/learning-goals", 999999)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blocks\":[]}"))
                .andExpect(status().isNotFound());
    }
}
