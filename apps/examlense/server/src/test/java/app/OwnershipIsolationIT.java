package app;

import app.shared.DefaultUser;
import app.exam.Exam;
import app.exam.ExamRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The core data-isolation guarantee. The authenticated principal is always the
 * single seeded user ({@link DefaultUser#ID}); this test seeds a second exam
 * owned by a DIFFERENT user and proves that principal can never read, mutate, or
 * delete it — a 403 across every representative controller. This is the guard
 * against a controller forgetting its {@code access.requireExam(...)} call.
 */
@AutoConfigureMockMvc
class OwnershipIsolationIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ExamRepository exams;

    private Exam seedExam(UUID owner, String title) {
        Exam e = new Exam();
        e.setOwnerId(owner);
        e.setTitle(title);
        e.setSource("manual");
        e.setStatus("draft");
        return exams.save(e);
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b) {
        return b.header("Authorization", "Bearer " + TEST_TOKEN);
    }

    @Test
    void ownerCanReadTheirOwnExam() throws Exception {
        Exam mine = seedExam(DefaultUser.ID, "mine");

        mvc.perform(auth(get("/api/exams/" + mine.getId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(mine.getId().toString()));
    }

    @Test
    void readingAnotherUsersExamIsForbidden() throws Exception {
        Exam foreign = seedExam(UUID.randomUUID(), "someone else's");

        mvc.perform(auth(get("/api/exams/" + foreign.getId())))
            .andExpect(status().isForbidden());
    }

    @Test
    void patchingAnotherUsersExamIsForbidden() throws Exception {
        Exam foreign = seedExam(UUID.randomUUID(), "someone else's");

        mvc.perform(auth(patch("/api/exams/" + foreign.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"hijacked\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void deletingAnotherUsersExamIsForbidden() throws Exception {
        Exam foreign = seedExam(UUID.randomUUID(), "someone else's");

        mvc.perform(auth(delete("/api/exams/" + foreign.getId())))
            .andExpect(status().isForbidden());
    }

    @Test
    void uploadingAPdfToAnotherUsersExamIsForbidden() throws Exception {
        Exam foreign = seedExam(UUID.randomUUID(), "someone else's");
        MockMultipartFile file = new MockMultipartFile("file", "exam.pdf", "application/pdf", "x".getBytes());

        mvc.perform(auth(multipart("/api/exams/" + foreign.getId() + "/pdf").file(file)))
            .andExpect(status().isForbidden());
    }

    @Test
    void unknownExamIsNotFound() throws Exception {
        mvc.perform(auth(get("/api/exams/" + UUID.randomUUID())))
            .andExpect(status().isNotFound());
    }

    @Test
    void listReturnsOnlyMyExamsNeverAnotherUsers() throws Exception {
        Exam mine = seedExam(DefaultUser.ID, "mine-" + UUID.randomUUID());
        Exam foreign = seedExam(UUID.randomUUID(), "foreign-" + UUID.randomUUID());

        mvc.perform(auth(get("/api/exams")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == '" + mine.getId() + "')]").exists())
            .andExpect(jsonPath("$[?(@.id == '" + foreign.getId() + "')]").doesNotExist());
    }
}
