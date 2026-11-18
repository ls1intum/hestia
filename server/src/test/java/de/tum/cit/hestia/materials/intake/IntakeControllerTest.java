package de.tum.cit.hestia.materials.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.tum.cit.hestia.materials.MaterialsTestBase;
import de.tum.cit.hestia.materials.MaterialsTestConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

// Material intake over HTTP: login, terms, confirmations, own list, admin list and download.
@Import(MaterialsTestConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class IntakeControllerTest extends MaterialsTestBase {

    private static final String BASE = "/learninggoalhub/api/intake";
    private static final String ADMIN = "intake-admin";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IntakeTerms terms;

    @Test
    void needsALogin() throws Exception {
        mockMvc.perform(get(BASE + "/terms").with(anonymous())).andExpect(status().isUnauthorized());
        mockMvc.perform(submission(pdf("a.pdf")).with(anonymous())).andExpect(status().isUnauthorized());
    }

    @Test
    void servesTheTermsWithVersionAndHash() throws Exception {
        mockMvc.perform(get(BASE + "/terms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(terms.current().version()))
                .andExpect(jsonPath("$.sha256").value(terms.current().sha256()))
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("Nutzungsbedingungen")));
    }

    @Test
    void meNamesTheUserAndTheAdminFlag() throws Exception {
        mockMvc.perform(get(BASE + "/me").with(user("someone")))
                .andExpect(jsonPath("$.id").value("someone"))
                .andExpect(jsonPath("$.admin").value(false));
        mockMvc.perform(get(BASE + "/me").with(user(ADMIN)))
                .andExpect(jsonPath("$.admin").value(true));
    }

    @Test
    void refusesASubmissionWithoutBothConfirmations() throws Exception {
        mockMvc.perform(multipart(BASE + "/submissions").file(pdf("a.pdf"))
                        .param("termsVersion", terms.current().version())
                        .param("termsSha256", terms.current().sha256())
                        .param("confirmedRights", "true"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(multipart(BASE + "/submissions").file(pdf("a.pdf"))
                        .param("termsVersion", terms.current().version())
                        .param("termsSha256", terms.current().sha256())
                        .param("confirmedNoStudentData", "true"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refusesTermsThatAreNotTheCurrentOnes() throws Exception {
        mockMvc.perform(multipart(BASE + "/submissions").file(pdf("a.pdf"))
                        .param("termsVersion", terms.current().version())
                        .param("termsSha256", "0".repeat(64))
                        .param("confirmedRights", "true")
                        .param("confirmedNoStudentData", "true"))
                .andExpect(status().isConflict());
    }

    @Test
    void refusesUnknownFileTypesAndEmptySubmissions() throws Exception {
        mockMvc.perform(submission(new MockMultipartFile("files", "run.exe", "application/octet-stream", new byte[] {1})))
                .andExpect(status().isUnsupportedMediaType());
        mockMvc.perform(submission(new MockMultipartFile("files", "empty.pdf", "application/pdf", new byte[0])))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refusesAnInvalidContactEmail() throws Exception {
        mockMvc.perform(submission(pdf("a.pdf")).param("contactEmail", "not an address"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void storesASubmissionThatOnlyItsSubmitterAndTheAdminSee() throws Exception {
        String submitter = "teacher-" + UUID.randomUUID();
        byte[] slides = "%PDF-1.4 slides".getBytes(StandardCharsets.UTF_8);

        String body = mockMvc.perform(submission(new MockMultipartFile("files", "Folien 1.pdf", "application/pdf", slides),
                                new MockMultipartFile("files", "Klausur.docx", "application/octet-stream", new byte[] {7, 7}))
                        .param("contactEmail", " teacher@example.org ")
                        .param("note", "Klausur WS 2025")
                        .param("consentExternalAi", "true")
                        .with(user(submitter)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.submitterId").value(submitter))
                .andExpect(jsonPath("$.contactEmail").value("teacher@example.org"))
                .andExpect(jsonPath("$.termsVersion").value(terms.current().version()))
                .andExpect(jsonPath("$.consentExternalAi").value(true))
                .andExpect(jsonPath("$.consentPublication").value(false))
                .andExpect(jsonPath("$.files.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        JsonNode created = objectMapper.readTree(body);
        String submissionId = created.get("id").asText();
        String slidesId = null;
        for (JsonNode file : created.get("files")) {
            if (file.get("name").asText().equals("Folien 1.pdf")) {
                slidesId = file.get("id").asText();
            }
        }
        assertThat(slidesId).isNotNull();

        // The submitter sees it, another user does not.
        mockMvc.perform(get(BASE + "/submissions").with(user(submitter)))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(submissionId));
        mockMvc.perform(get(BASE + "/submissions").with(user("other-" + UUID.randomUUID())))
                .andExpect(jsonPath("$.length()").value(0));

        // Only the admin lists everything and downloads the original bytes.
        mockMvc.perform(get(BASE + "/admin/submissions").with(user(submitter))).andExpect(status().isForbidden());
        mockMvc.perform(get(BASE + "/admin/submissions/{s}/files/{f}", submissionId, slidesId).with(user(submitter)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(BASE + "/admin/submissions").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + submissionId + "')]").exists());
        byte[] downloaded = mockMvc.perform(get(BASE + "/admin/submissions/{s}/files/{f}", submissionId, slidesId)
                        .with(user(ADMIN)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(downloaded).isEqualTo(slides);

        // Deleting takes the files with it.
        mockMvc.perform(delete(BASE + "/admin/submissions/{s}", submissionId).with(user(submitter)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(BASE + "/admin/submissions/{s}", submissionId).with(user(ADMIN)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get(BASE + "/submissions").with(user(submitter)))
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get(BASE + "/admin/submissions/{s}/files/{f}", submissionId, slidesId).with(user(ADMIN)))
                .andExpect(status().isNotFound());
    }

    private MockMultipartHttpServletRequestBuilder submission(MockMultipartFile... files) {
        MockMultipartHttpServletRequestBuilder request = multipart(BASE + "/submissions");
        for (MockMultipartFile file : files) {
            request.file(file);
        }
        request.param("termsVersion", terms.current().version())
                .param("termsSha256", terms.current().sha256())
                .param("confirmedRights", "true")
                .param("confirmedNoStudentData", "true");
        return request;
    }

    private static MockMultipartFile pdf(String name) {
        return new MockMultipartFile("files", name, "application/pdf", "%PDF-1.4".getBytes(StandardCharsets.UTF_8));
    }
}
