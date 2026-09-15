package app;

import app.examination.Examination;
import app.examination.ExaminationRepository;
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
 * a controller forgetting its {@code access.requireExamination(...)} call, and against a
 * regression in the filter handing out the wrong principal.
 */
@AutoConfigureMockMvc
class OwnershipIsolationIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ExaminationRepository exams;

    private TestUser alice;
    private TestUser bob;

    @BeforeEach
    void seedUsers() {
        alice = createUser("alice-" + UUID.randomUUID());
        bob = createUser("bob-" + UUID.randomUUID());
    }

    private Examination seedExamination(UUID owner, String title) {
        Examination e = new Examination();
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
    void ownerCanReadTheirOwnExamination() throws Exception {
        Examination mine = seedExamination(alice.id(), "mine");

        mvc.perform(asAlice(get("/api/exams/" + mine.getId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(mine.getId().toString()));
    }

    @Test
    void readingAnotherUsersExaminationIsForbidden() throws Exception {
        Examination foreign = seedExamination(alice.id(), "alice's");

        mvc.perform(asBob(get("/api/exams/" + foreign.getId())))
            .andExpect(status().isForbidden());
    }

    @Test
    void patchingAnotherUsersExaminationIsForbidden() throws Exception {
        Examination foreign = seedExamination(alice.id(), "alice's");

        mvc.perform(asBob(patch("/api/exams/" + foreign.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"hijacked\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void deletingAnotherUsersExaminationIsForbidden() throws Exception {
        Examination foreign = seedExamination(alice.id(), "alice's");

        mvc.perform(asBob(delete("/api/exams/" + foreign.getId())))
            .andExpect(status().isForbidden());
    }

    @Test
    void uploadingAPdfToAnotherUsersExaminationIsForbidden() throws Exception {
        Examination foreign = seedExamination(alice.id(), "alice's");
        MockMultipartFile file = new MockMultipartFile("file", "exam.pdf", "application/pdf", "x".getBytes());

        mvc.perform(asBob(multipart("/api/exams/" + foreign.getId() + "/pdf").file(file)))
            .andExpect(status().isForbidden());
    }

    @Test
    void unknownExaminationIsNotFound() throws Exception {
        mvc.perform(asBob(get("/api/exams/" + UUID.randomUUID())))
            .andExpect(status().isNotFound());
    }

    @Test
    void listReturnsOnlyMyExaminationsNeverAnotherUsers() throws Exception {
        Examination alices = seedExamination(alice.id(), "alice-" + UUID.randomUUID());
        Examination bobs = seedExamination(bob.id(), "bob-" + UUID.randomUUID());

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
    void freshUserSeesNoExaminationsAtAll() throws Exception {
        seedExamination(alice.id(), "alice-" + UUID.randomUUID());
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
