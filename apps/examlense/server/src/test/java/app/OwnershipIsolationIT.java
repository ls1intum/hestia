package app;

import app.exam.Exam;
import app.exam.ExamRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
 * The core data-isolation guarantee. Two real users, each holding their own
 * token, and the proof that neither can read, mutate, or delete the other's exam
 * — a 403 across every representative controller, plus absence from the list.
 *
 * <p>Both identities are genuine here (their own {@code users} row and their own
 * {@code user_tokens} row), so this exercises the whole path from bearer token
 * through principal resolution to the ownership check. That is the guard against
 * a controller forgetting its {@code access.requireExam(...)} call, and against a
 * regression in the filter handing out the wrong principal.
 */
@AutoConfigureMockMvc
class OwnershipIsolationIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ExamRepository exams;

    private TestUser alice;
    private TestUser bob;

    @BeforeEach
    void seedUsers() {
        alice = createUser("alice-" + UUID.randomUUID());
        bob = createUser("bob-" + UUID.randomUUID());
    }

    private Exam seedExam(UUID owner, String title) {
        Exam e = new Exam();
        e.setOwnerId(owner);
        e.setTitle(title);
        e.setSource("manual");
        e.setStatus("draft");
        return exams.save(e);
    }

    /** Authenticate as Bob, who owns nothing in these tests. */
    private MockHttpServletRequestBuilder asBob(MockHttpServletRequestBuilder b) {
        return b.header("Authorization", "Bearer " + bob.token());
    }

    private MockHttpServletRequestBuilder asAlice(MockHttpServletRequestBuilder b) {
        return b.header("Authorization", "Bearer " + alice.token());
    }

    @Test
    void ownerCanReadTheirOwnExam() throws Exception {
        Exam mine = seedExam(alice.id(), "mine");

        mvc.perform(asAlice(get("/api/exams/" + mine.getId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(mine.getId().toString()));
    }

    @Test
    void readingAnotherUsersExamIsForbidden() throws Exception {
        Exam foreign = seedExam(alice.id(), "alice's");

        mvc.perform(asBob(get("/api/exams/" + foreign.getId())))
            .andExpect(status().isForbidden());
    }

    @Test
    void patchingAnotherUsersExamIsForbidden() throws Exception {
        Exam foreign = seedExam(alice.id(), "alice's");

        mvc.perform(asBob(patch("/api/exams/" + foreign.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"hijacked\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void deletingAnotherUsersExamIsForbidden() throws Exception {
        Exam foreign = seedExam(alice.id(), "alice's");

        mvc.perform(asBob(delete("/api/exams/" + foreign.getId())))
            .andExpect(status().isForbidden());
    }

    @Test
    void uploadingAPdfToAnotherUsersExamIsForbidden() throws Exception {
        Exam foreign = seedExam(alice.id(), "alice's");
        MockMultipartFile file = new MockMultipartFile("file", "exam.pdf", "application/pdf", "x".getBytes());

        mvc.perform(asBob(multipart("/api/exams/" + foreign.getId() + "/pdf").file(file)))
            .andExpect(status().isForbidden());
    }

    @Test
    void unknownExamIsNotFound() throws Exception {
        mvc.perform(asBob(get("/api/exams/" + UUID.randomUUID())))
            .andExpect(status().isNotFound());
    }

    @Test
    void listReturnsOnlyMyExamsNeverAnotherUsers() throws Exception {
        Exam alices = seedExam(alice.id(), "alice-" + UUID.randomUUID());
        Exam bobs = seedExam(bob.id(), "bob-" + UUID.randomUUID());

        mvc.perform(asBob(get("/api/exams")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == '" + bobs.getId() + "')]").exists())
            .andExpect(jsonPath("$[?(@.id == '" + alices.getId() + "')]").doesNotExist());
    }

    /**
     * A brand-new user sees an empty dashboard. Worth pinning separately from the
     * filtering test above: the regression this change exists to prevent was
     * every user seeing every exam, which an "is mine present" assertion alone
     * would not have caught.
     */
    @Test
    void freshUserSeesNoExamsAtAll() throws Exception {
        seedExam(alice.id(), "alice-" + UUID.randomUUID());
        TestUser newcomer = createUser("newcomer-" + UUID.randomUUID());

        mvc.perform(get("/api/exams").header("Authorization", "Bearer " + newcomer.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void noTokenIsUnauthorized() throws Exception {
        mvc.perform(get("/api/exams")).andExpect(status().isUnauthorized());
    }

    @Test
    void unknownTokenIsUnauthorized() throws Exception {
        mvc.perform(get("/api/exams").header("Authorization", "Bearer exl_not-a-real-token"))
            .andExpect(status().isUnauthorized());
    }
}
