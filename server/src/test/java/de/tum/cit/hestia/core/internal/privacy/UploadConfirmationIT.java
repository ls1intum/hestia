package de.tum.cit.hestia.core.internal.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import de.tum.cit.hestia.TestDatabase;
import de.tum.cit.hestia.core.CourseApi;
import de.tum.cit.hestia.core.TestLogin;
import de.tum.cit.hestia.core.internal.identity.IdentityService;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Upload confirmation: every module refuses uploads with 428 UPLOAD_CONFIRMATION_REQUIRED until the
 * user confirmed the current notice once; the confirmation is logged append-only, per user and
 * organisation.
 */
@SpringBootTest(properties = "hestia.privacy.upload-confirmation.enforced=true")
@AutoConfigureMockMvc
class UploadConfirmationIT {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @MockitoBean
    RelyingPartyRegistrationRepository relyingPartyRegistrationRepository;

    @Autowired MockMvc mvc;
    @Autowired IdentityService identities;
    @Autowired CourseApi courseApi;

    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final String uniA = "upload-a-" + run + ".test";
    private final String uniB = "upload-b-" + run + ".test";

    private static final String CONFIRMATION = "/core/api/privacy/upload-confirmation";
    private static final String VERSION = UploadConfirmationService.NOTICE_VERSION;

    private RequestPostProcessor as(String org, String user) {
        return TestLogin.as(identities, org, user);
    }

    private void confirm(String org, String user) throws Exception {
        mvc.perform(post(CONFIRMATION).with(as(org, user)).contentType(APPLICATION_JSON)
                        .content("{\"noticeVersion\":\"" + VERSION + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmed").value(true));
    }

    private int uploadDocument(String user, UUID course) throws Exception {
        var file = new MockMultipartFile("files", "slides.pdf", "application/pdf", "not a pdf".getBytes());
        return mvc.perform(multipart("/learninggoalhub/api/courses/" + course + "/documents").file(file)
                        .with(as(uniA, user)))
                .andReturn().getResponse().getStatus();
    }

    private int uploadTemplate(String user, UUID course) throws Exception {
        var file = new MockMultipartFile("template", "template.pptx", "application/octet-stream", new byte[] {1, 2});
        return mvc.perform(multipart("/workshopper/api/workshop/courses/" + course + "/template").file(file)
                        .with(as(uniA, user)))
                .andReturn().getResponse().getStatus();
    }

    private int uploadExamPdf(String user, UUID course) throws Exception {
        String json = mvc.perform(post("/examlense/api/exams").with(as(uniA, user)).contentType(APPLICATION_JSON)
                        .content("{\"title\":\"t\",\"source\":\"manual\",\"course_id\":\"" + course + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String examId = JsonPath.read(json, "$.id");
        var file = new MockMultipartFile("file", "exam.pdf", "application/pdf", "not a pdf".getBytes());
        return mvc.perform(multipart("/examlense/api/exams/" + examId + "/pdf").file(file).with(as(uniA, user)))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void everyModuleRefusesUploadsUntilTheUserConfirmedOnce() throws Exception {
        UUID course = TestLogin.runAs(identities, uniA, "alice", () -> courseApi.create("Algorithms").id());

        mvc.perform(get(CONFIRMATION).with(as(uniA, "alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmed").value(false))
                .andExpect(jsonPath("$.noticeVersion").value(VERSION))
                .andExpect(jsonPath("$.checkboxLabel").isNotEmpty())
                .andExpect(jsonPath("$.text").isNotEmpty());

        var file = new MockMultipartFile("files", "slides.pdf", "application/pdf", "x".getBytes());
        mvc.perform(multipart("/learninggoalhub/api/courses/" + course + "/documents").file(file)
                        .with(as(uniA, "alice")))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("UPLOAD_CONFIRMATION_REQUIRED"));
        assertThat(uploadTemplate("alice", course)).isEqualTo(428);
        assertThat(uploadExamPdf("alice", course)).isEqualTo(428);

        // An outdated notice version is refused; the current one is recorded.
        mvc.perform(post(CONFIRMATION).with(as(uniA, "alice")).contentType(APPLICATION_JSON)
                        .content("{\"noticeVersion\":\"2000-01-01\"}"))
                .andExpect(status().isConflict());
        confirm(uniA, "alice");
        mvc.perform(get(CONFIRMATION).with(as(uniA, "alice")))
                .andExpect(jsonPath("$.confirmed").value(true))
                .andExpect(jsonPath("$.confirmedAt").isNotEmpty());

        // Now the uploads reach the modules' own checks (these files are no valid PDF/PPTX).
        assertThat(uploadDocument("alice", course)).isNotEqualTo(428).isLessThan(500);
        assertThat(uploadTemplate("alice", course)).isNotEqualTo(428);
        assertThat(uploadExamPdf("alice", course)).isNotEqualTo(428).isLessThan(500);
    }

    @Test
    void aConfirmationCountsForItsUserAndOrganisationOnly() throws Exception {
        confirm(uniA, "alice");
        confirm(uniA, "alice"); // a second click adds no entry

        mvc.perform(get(CONFIRMATION).with(as(uniA, "bob"))).andExpect(jsonPath("$.confirmed").value(false));
        // Same external id at another university: another user.
        mvc.perform(get(CONFIRMATION).with(as(uniB, "alice"))).andExpect(jsonPath("$.confirmed").value(false));

        UUID aliceA = TestLogin.login(identities, uniA, "alice").userId();
        UUID orgA = TestLogin.login(identities, uniA, "alice").orgId();
        UUID orgB = TestLogin.login(identities, uniB, "alice").orgId();
        try (Connection c = appConnection()) {
            assertThat(count(c, orgA, aliceA)).isEqualTo(1);
            assertThat(count(c, orgB, aliceA)).isZero();
            assertThat(count(c, null, aliceA)).isZero();
        }
    }

    @Test
    void theLogIsAppendOnlyForTheApplication() throws Exception {
        confirm(uniA, "carol");
        UUID carol = TestLogin.login(identities, uniA, "carol").userId();
        UUID orgA = TestLogin.login(identities, uniA, "carol").orgId();
        try (Connection c = appConnection()) {
            setOrg(c, orgA);
            assertThatThrownBy(() -> c.createStatement().executeUpdate(
                    "update core.upload_confirmation set notice_version = 'x' where user_id = '" + carol + "'"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
            assertThatThrownBy(() -> c.createStatement().executeUpdate(
                    "delete from core.upload_confirmation where user_id = '" + carol + "'"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
        }
    }

    private static Connection appConnection() throws SQLException {
        return DriverManager.getConnection(TestDatabase.container().getJdbcUrl(), "hestia_app", "test-app");
    }

    private static void setOrg(Connection c, UUID org) throws SQLException {
        try (var set = c.prepareStatement("select set_config('hestia.org_id', ?, false)")) {
            set.setString(1, org == null ? "" : org.toString());
            set.execute();
        }
    }

    private static int count(Connection c, UUID org, UUID user) throws SQLException {
        setOrg(c, org);
        try (var q = c.prepareStatement("select count(*) from core.upload_confirmation where user_id = ?")) {
            q.setObject(1, user);
            try (ResultSet rs = q.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
