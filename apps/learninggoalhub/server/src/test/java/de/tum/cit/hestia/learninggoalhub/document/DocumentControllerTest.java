package de.tum.cit.hestia.learninggoalhub.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.tum.cit.hestia.learninggoalhub.TestcontainersConfiguration;
import de.tum.cit.hestia.learninggoalhub.course.CourseRepository;
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

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private CourseRepository courseRepository;

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
    void listsCourseDocumentsInUploadOrder() throws Exception {
        MvcResult courseResult = mockMvc.perform(post("/api/courses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Databases\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode course = objectMapper.readTree(courseResult.getResponse().getContentAsString());
        long courseId = course.get("id").asLong();

        byte[] pdf = getClass().getResourceAsStream("/parser/sample.pdf").readAllBytes();
        mockMvc.perform(multipart("/api/courses/{id}/documents", courseId)
                        .file(new MockMultipartFile("files", "lecture-01.pdf", MediaType.APPLICATION_PDF_VALUE, pdf)))
                .andExpect(status().isCreated());
        mockMvc.perform(multipart("/api/courses/{id}/documents", courseId)
                        .file(new MockMultipartFile("files", "lecture-02.pdf", MediaType.APPLICATION_PDF_VALUE, pdf)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/courses/{id}/documents", courseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].filename").value("lecture-01.pdf"))
                .andExpect(jsonPath("$[1].filename").value("lecture-02.pdf"));
    }

    @Test
    void uploadsAndServesDocumentContentForBrowserViewer() throws Exception {
        long courseId = createCourse("Distributed Systems");
        byte[] pdf = getClass().getResourceAsStream("/parser/sample.pdf").readAllBytes();
        MvcResult upload = mockMvc.perform(multipart("/api/courses/{id}/documents", courseId)
                        .file(new MockMultipartFile("files", "lecture.pdf", MediaType.APPLICATION_PDF_VALUE, pdf)))
                .andExpect(status().isCreated())
                .andReturn();
        long documentId = objectMapper.readTree(upload.getResponse().getContentAsString())
                .get(0).get("id").asLong();

        mockMvc.perform(get("/api/courses/{courseId}/documents/{documentId}/content", courseId, documentId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().longValue("Content-Length", pdf.length))
                .andExpect(header().string("Content-Disposition",
                        "inline; filename*=UTF-8''lecture.pdf"))
                .andExpect(content().bytes(pdf));
    }

    @Test
    void persistsPdfPageOffsetsAndRejectsWrongCourseOrLegacyContent() throws Exception {
        long courseId = createCourse("Programming Languages");
        long otherCourseId = createCourse("Databases");
        byte[] pdf = getClass().getResourceAsStream("/parser/sample.pdf").readAllBytes();
        MvcResult upload = mockMvc.perform(multipart("/api/courses/{id}/documents", courseId)
                        .file(new MockMultipartFile("files", "lecture.pdf", MediaType.APPLICATION_PDF_VALUE, pdf)))
                .andExpect(status().isCreated())
                .andReturn();
        long documentId = objectMapper.readTree(upload.getResponse().getContentAsString())
                .get(0).get("id").asLong();

        Document persisted = documentRepository.findById(documentId).orElseThrow();
        assertThat(persisted.getPageOffsets()).isNotNull().hasSizeGreaterThan(1);
        assertThat(persisted.getPageOffsets()[0]).isZero();
        assertThat(persisted.getPageOffsets()[persisted.getPageOffsets().length - 1])
                .isEqualTo(persisted.getRawText().length());

        mockMvc.perform(get("/api/courses/{courseId}/documents/{documentId}/content",
                        otherCourseId, documentId))
                .andExpect(status().isNotFound());

        Document legacy = documentRepository.save(
                new Document(courseRepository.findById(courseId).orElseThrow(),
                        "legacy.pdf", MediaType.APPLICATION_PDF_VALUE, "legacy"));
        mockMvc.perform(get("/api/courses/{courseId}/documents/{documentId}/content",
                        courseId, legacy.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    void listReturns404WhenCourseMissing() throws Exception {
        mockMvc.perform(get("/api/courses/{id}/documents", 999999L))
                .andExpect(status().isNotFound());
    }

    @Test
    void renamesDocumentAndClearsBackToFilename() throws Exception {
        long courseId = createCourse("Operating Systems");
        long documentId = uploadDocument(courseId, "lecture-01.pdf");

        mockMvc.perform(patch("/api/courses/{courseId}/documents/{documentId}", courseId, documentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"  Scheduling  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Scheduling"))
                .andExpect(jsonPath("$.filename").value("lecture-01.pdf"));

        mockMvc.perform(patch("/api/courses/{courseId}/documents/{documentId}", courseId, documentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").doesNotExist())
                .andExpect(jsonPath("$.filename").value("lecture-01.pdf"));
    }

    @Test
    void rejectsBlankDisplayName() throws Exception {
        long courseId = createCourse("Compilers");
        long documentId = uploadDocument(courseId, "lecture-01.pdf");

        mockMvc.perform(patch("/api/courses/{courseId}/documents/{documentId}", courseId, documentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void renameReturns404WhenDocumentBelongsToOtherCourse() throws Exception {
        long courseId = createCourse("Networks");
        long otherCourseId = createCourse("Security");
        long documentId = uploadDocument(courseId, "lecture-01.pdf");

        mockMvc.perform(patch("/api/courses/{courseId}/documents/{documentId}", otherCourseId, documentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"X\"}"))
                .andExpect(status().isNotFound());
    }

    private long createCourse(String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/courses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private long uploadDocument(long courseId, String filename) throws Exception {
        byte[] pdf = getClass().getResourceAsStream("/parser/sample.pdf").readAllBytes();
        MvcResult result = mockMvc.perform(multipart("/api/courses/{id}/documents", courseId)
                        .file(new MockMultipartFile("files", filename, MediaType.APPLICATION_PDF_VALUE, pdf)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get(0).get("id").asLong();
    }

    @Test
    void returns404WhenCourseMissing() throws Exception {
        MockMultipartFile file = new MockMultipartFile("files", "x.pdf", MediaType.APPLICATION_PDF_VALUE, new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/api/courses/{id}/documents", 999999L).file(file))
                .andExpect(status().isNotFound());
    }
}
