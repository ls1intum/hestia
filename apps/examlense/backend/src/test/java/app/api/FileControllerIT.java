package app.api;

import app.AbstractIntegrationTest;
import app.persistence.DefaultUser;
import app.persistence.entity.Exam;
import app.persistence.repository.ExamRepository;
import app.storage.SignedUrls;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end for the private-file path: an owner uploads a PDF, and it is only
 * retrievable through a valid HMAC-signed URL. The file route is public, so the
 * signature IS the authorization — a tampered/absent signature must be rejected.
 */
@AutoConfigureMockMvc
class FileControllerIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ExamRepository exams;
    @Autowired SignedUrls signedUrls;

    private Exam ownExam() {
        Exam e = new Exam();
        e.setOwnerId(DefaultUser.ID);
        e.setSource("pdf");
        e.setStatus("draft");
        return exams.save(e);
    }

    @Test
    void uploadThenFetchViaSignedUrlRoundTrips() throws Exception {
        Exam exam = ownExam();
        byte[] pdf = "%PDF-1.4 fake".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "exam.pdf", "application/pdf", pdf);

        String storagePath = extractPath(mvc.perform(multipart("/api/exams/" + exam.getId() + "/pdf")
                .file(file)
                .header("Authorization", "Bearer " + TEST_TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.storage_path").exists())
            .andReturn().getResponse().getContentAsString());

        String signed = signedUrls.buildUrl("exam-pdfs", storagePath, 300);

        // Public route — no token, signature does the gating.
        mvc.perform(get(signed))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andExpect(content().bytes(pdf));
    }

    @Test
    void fetchWithATamperedSignatureIsForbidden() throws Exception {
        Exam exam = ownExam();
        // Must be a real PDF (magic bytes) or the upload is rejected before we get a path.
        MockMultipartFile file = new MockMultipartFile("file", "exam.pdf", "application/pdf", "%PDF-1.4 x".getBytes());
        String storagePath = extractPath(mvc.perform(multipart("/api/exams/" + exam.getId() + "/pdf")
                .file(file)
                .header("Authorization", "Bearer " + TEST_TOKEN))
            .andReturn().getResponse().getContentAsString());

        String signed = signedUrls.buildUrl("exam-pdfs", storagePath, 300);
        String tampered = signed.replaceAll("sig=.*$", "sig=forged");

        mvc.perform(get(tampered)).andExpect(status().isForbidden());
    }

    @Test
    void uploadRequiresAToken() throws Exception {
        Exam exam = ownExam();
        MockMultipartFile file = new MockMultipartFile("file", "exam.pdf", "application/pdf", "x".getBytes());

        mvc.perform(multipart("/api/exams/" + exam.getId() + "/pdf").file(file))
            .andExpect(status().isUnauthorized());
    }

    /** storage_path is {userId}/{examId}.pdf — pull it out of the JSON body. */
    private static String extractPath(String json) {
        return json.replaceAll(".*\"storage_path\"\\s*:\\s*\"([^\"]+)\".*", "$1");
    }
}
