package de.tum.cit.hestia.learninggoalhub.document;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.tum.cit.hestia.learninggoalhub.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class DocumentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // Bookmark-less PDFs would otherwise trigger a real vision-model call at upload; the default mock
    // returns null, exercising the filename-fallback path without touching the network.
    @MockitoBean
    private DocumentTitleService titleService;

    // A bookmarked PDF would otherwise ask the model whether to split; the default mock keeps us off
    // the network (returns false → the document is treated as a single session).
    @MockitoBean
    private BookmarkRelevanceJudge bookmarkJudge;

    @Test
    void uploadsMultiplePdfsToCourse() throws Exception {
        MvcResult courseResult = mockMvc.perform(post("/api/courses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Software Engineering\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode course = objectMapper.readTree(courseResult.getResponse().getContentAsString());
        long courseId = course.get("id").asLong();

        byte[] pdf = getClass().getResourceAsStream("/parser/sample.pdf").readAllBytes();
        MockMultipartFile lecture = new MockMultipartFile("files", "lecture-01.pdf", MediaType.APPLICATION_PDF_VALUE, pdf);
        MockMultipartFile exercise = new MockMultipartFile("files", "exercise-01.pdf", MediaType.APPLICATION_PDF_VALUE, pdf);

        mockMvc.perform(multipart("/api/courses/{id}/documents", courseId)
                        .file(lecture)
                        .file(exercise))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].filename").value("lecture-01.pdf"))
                .andExpect(jsonPath("$[0].courseId").value(courseId))
                .andExpect(jsonPath("$[0].contentType").value(MediaType.APPLICATION_PDF_VALUE))
                .andExpect(jsonPath("$[0].uploadedAt").exists())
                .andExpect(jsonPath("$[1].filename").value("exercise-01.pdf"));
    }

    @Test
    void returns404WhenCourseMissing() throws Exception {
        MockMultipartFile file = new MockMultipartFile("files", "x.pdf", MediaType.APPLICATION_PDF_VALUE, new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/api/courses/{id}/documents", 999999L).file(file))
                .andExpect(status().isNotFound());
    }
}
