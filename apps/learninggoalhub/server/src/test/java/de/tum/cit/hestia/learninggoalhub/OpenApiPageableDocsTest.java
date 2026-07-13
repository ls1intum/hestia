package de.tum.cit.hestia.learninggoalhub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

// Pageable endpoints must expose flat page/size/sort query params (via @ParameterObject) rather than
// a single nested `pageable` object. Swagger UI's "Try it out" pre-fills a nested object's sort with
// the placeholder ["string"], which submits sort=string and makes Spring Data 500 on an unknown
// property. Flat params start empty, so the default sort is used and the call succeeds.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiPageableDocsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void pageableEndpointsDocumentFlatPageSizeSortParams() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode paths = objectMapper.readTree(body).get("paths");

        for (String path : List.of("/api/courses", "/api/courses/{courseId}/learning-goals")) {
            List<String> paramNames = new ArrayList<>();
            paths.get(path).get("get").get("parameters").forEach(p -> paramNames.add(p.get("name").asText()));
            assertThat(paramNames)
                    .as("flat pageable params for GET %s", path)
                    .contains("page", "size", "sort")
                    .doesNotContain("pageable");
        }
    }
}
