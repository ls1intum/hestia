package de.tum.cit.hestia.learninggoalhub.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

// Service keys enforced and a small rate limit. Each test sends from its own address, since the
// limiter counts per client address across the shared context.
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "hestia.security.api-key.examlense=examlense-test-key",
        "hestia.security.enforce-service-keys=true",
        "hestia.rate-limit.llm-requests=2"})
@AutoConfigureMockMvc
class ServiceApiKeyTest {

    private static final String EXAM_TASKS = "/api/courses/{id}/exam-tasks/learning-goals";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void serviceOnlyEndpointRejectsAMissingOrWrongKey() throws Exception {
        mockMvc.perform(from("10.0.0.1", examTasks()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(from("10.0.0.1", examTasks().header(ServiceApiKeyFilter.HEADER, "wrong")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(from("10.0.0.1", get("/api/courses/{id}/learning-goals/by-session", 1)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validKeyReachesTheEndpoint() throws Exception {
        // Past security, the unknown course is the endpoint's own 404.
        mockMvc.perform(from("10.0.0.2", examTasks().header(ServiceApiKeyFilter.HEADER, "examlense-test-key")))
                .andExpect(status().isNotFound());
    }

    @Test
    void endpointsTheClientUsesStayOpen() throws Exception {
        mockMvc.perform(from("10.0.0.3", get("/api/courses")))
                .andExpect(status().isOk());
    }

    @Test
    void llmRequestsBeyondTheLimitGet429() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(from("10.0.0.4", post("/api/courses/{id}/extract", 999999)))
                    .andExpect(status().isNotFound());
        }
        mockMvc.perform(from("10.0.0.4", post("/api/courses/{id}/extract", 999999)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));
        // Another client has its own allowance; reads are never counted.
        mockMvc.perform(from("10.0.0.5", post("/api/courses/{id}/extract", 999999)))
                .andExpect(status().isNotFound());
        mockMvc.perform(from("10.0.0.4", get("/api/courses")))
                .andExpect(status().isOk());
    }

    @Test
    void requestsWithAServiceKeyAreNotCounted() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(from("10.0.0.6", examTasks().header(ServiceApiKeyFilter.HEADER, "examlense-test-key")))
                    .andExpect(status().isNotFound());
        }
    }

    private static MockHttpServletRequestBuilder examTasks() {
        return post(EXAM_TASKS, 999999).contentType(MediaType.APPLICATION_JSON).content("{\"blocks\":[]}");
    }

    private static MockHttpServletRequestBuilder from(String address, MockHttpServletRequestBuilder request) {
        return request.with(r -> {
            r.setRemoteAddr(address);
            return r;
        });
    }
}
