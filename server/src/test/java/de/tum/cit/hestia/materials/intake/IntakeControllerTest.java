package de.tum.cit.hestia.materials.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.tum.cit.hestia.core.TestLogin;
import de.tum.cit.hestia.core.internal.identity.IdentityService;
import de.tum.cit.hestia.materials.MaterialsTestBase;
import de.tum.cit.hestia.materials.TestTenant;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import de.tum.cit.hestia.materials.MaterialsTestConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

// Material intake over HTTP: login, terms, confirmation, consents, own list, no access to others.
@Import(MaterialsTestConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class IntakeControllerTest extends MaterialsTestBase {

    private static final String BASE = "/learninggoalhub/api/intake";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private IntakeTerms terms;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private IdentityService identities;

    private RequestPostProcessor as(String user) {
        return TestLogin.as(identities, TestTenant.ORG, user);
    }

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
    void meNamesTheUser() throws Exception {
        mockMvc.perform(get(BASE + "/me").with(as("someone")))
                .andExpect(jsonPath("$.id").value("someone"));
    }

    @Test
    void refusesASubmissionWithoutTheConfirmation() throws Exception {
        mockMvc.perform(multipart(BASE + "/submissions").file(pdf("a.pdf"))
                        .param("termsVersion", terms.current().version())
                        .param("termsSha256", terms.current().sha256())
                        .param("consentResearch", "true"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refusesTermsThatAreNotTheCurrentOnes() throws Exception {
        mockMvc.perform(multipart(BASE + "/submissions").file(pdf("a.pdf"))
                        .param("termsVersion", terms.current().version())
                        .param("termsSha256", "0".repeat(64))
                        .param("confirmedTerms", "true"))
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
    void storesTheSubmissionWithItsConsentsAndOnlyItsSubmitterSeesIt() throws Exception {
        String submitter = "teacher-" + UUID.randomUUID();
        byte[] slides = "%PDF-1.4 slides".getBytes(StandardCharsets.UTF_8);

        String id = com.jayway.jsonpath.JsonPath.read(mockMvc.perform(
                        submission(new MockMultipartFile("files", "Folien 1.pdf", "application/pdf", slides),
                                new MockMultipartFile("files", "Klausur.docx", "application/octet-stream", new byte[] {7, 7}))
                        .param("courseTitle", " Software Engineering WS 2025/26 ")
                        .param("contactEmail", " teacher@example.org ")
                        .param("consentResearch", "true")
                        .with(as(submitter)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.courseTitle").value("Software Engineering WS 2025/26"))
                .andExpect(jsonPath("$.contactEmail").value("teacher@example.org"))
                .andExpect(jsonPath("$.termsVersion").value(terms.current().version()))
                .andExpect(jsonPath("$.consentResearch").value(true))
                .andExpect(jsonPath("$.consentReplicationPackage").value(false))
                .andExpect(jsonPath("$.files.length()").value(2))
                .andReturn().getResponse().getContentAsString(), "$.id");

        // What was accepted is on the row, the bytes are stored unchanged.
        assertThat(jdbc.queryForObject("""
                select submitter_id || '|' || terms_sha256 || '|' || confirmed_terms
                from materials.intake_submission where id = ?::uuid""", String.class, id))
                .isEqualTo(submitter + "|" + terms.current().sha256() + "|true");
        assertThat(jdbc.queryForObject("""
                select bytes from materials.intake_file where submission_id = ?::uuid and original_name = 'Folien 1.pdf'""",
                byte[].class, id)).isEqualTo(slides);

        // The submitter sees it, another user does not.
        mockMvc.perform(get(BASE + "/submissions").with(as(submitter)))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(id));
        mockMvc.perform(get(BASE + "/submissions").with(as("other-" + UUID.randomUUID())))
                .andExpect(jsonPath("$.length()").value(0));
        // The same external id at another organisation is another user: nothing there either.
        mockMvc.perform(get(BASE + "/submissions")
                        .with(TestLogin.as(identities, "other-uni-" + UUID.randomUUID() + ".test", submitter)))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void researchConsentCanBeWithdrawnAtUpload() throws Exception {
        mockMvc.perform(submission(pdf("a.pdf")).param("consentReplicationPackage", "true")
                        .with(as("teacher-" + UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.consentResearch").value(false))
                .andExpect(jsonPath("$.consentReplicationPackage").value(true));
    }

    @Test
    void nobodyCanListOrDownloadOtherPeoplesSubmissions() throws Exception {
        mockMvc.perform(get(BASE + "/admin/submissions")).andExpect(status().isNotFound());
    }

    private MockMultipartHttpServletRequestBuilder submission(MockMultipartFile... files) {
        MockMultipartHttpServletRequestBuilder request = multipart(BASE + "/submissions");
        for (MockMultipartFile file : files) {
            request.file(file);
        }
        request.param("termsVersion", terms.current().version())
                .param("termsSha256", terms.current().sha256())
                .param("confirmedTerms", "true");
        return request;
    }

    private static MockMultipartFile pdf(String name) {
        return new MockMultipartFile("files", name, "application/pdf", "%PDF-1.4".getBytes(StandardCharsets.UTF_8));
    }
}
