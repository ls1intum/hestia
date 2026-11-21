package de.tum.cit.hestia.core.internal.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import de.tum.cit.hestia.TestDatabase;
import de.tum.cit.hestia.assessment.examination.ExaminationRepository;
import de.tum.cit.hestia.assessment.storage.StorageService;
import de.tum.cit.hestia.core.CourseApi;
import de.tum.cit.hestia.core.OrgContext;
import de.tum.cit.hestia.core.TestLogin;
import de.tum.cit.hestia.core.UploadConfirmation;
import de.tum.cit.hestia.core.internal.course.CourseRelease;
import de.tum.cit.hestia.core.internal.identity.AppUserRepository;
import de.tum.cit.hestia.core.internal.identity.IdentityService;
import de.tum.cit.hestia.core.internal.identity.OrganisationRepository;
import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Inactivity deletion: warnings at 300 and 350 days, deletion at 365 days (only after the last
 * warning stood its 15 days), solo courses deleted with their stored files, shared courses kept with
 * a new owner, a login resets the period, organisations stay apart, a dry run writes nothing, and
 * only one run at a time.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InactivityDeletionIT {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @MockitoBean
    RelyingPartyRegistrationRepository relyingPartyRegistrationRepository;

    @Autowired MockMvc mvc;
    @Autowired IdentityService identities;
    @Autowired CourseApi courseApi;
    @Autowired UploadConfirmation uploadConfirmation;
    @Autowired InactivitySweep sweep;
    @Autowired AppUserRepository users;
    @Autowired InactivityWarningRepository warnings;
    @Autowired ExaminationRepository exams;
    @Autowired StorageService storage;
    @Autowired OrganisationRepository organisations;
    @Autowired CourseRelease courseRelease;
    @Autowired InactivityNotifier notifier;
    @Autowired TransactionTemplate tx;
    @Autowired DataSource dataSource;

    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final String uni = "inactive-a-" + run + ".test";
    private final String otherUni = "inactive-b-" + run + ".test";

    private RequestPostProcessor as(String user) {
        return TestLogin.as(identities, uni, user);
    }

    private UUID userId(String org, String user) {
        return TestLogin.login(identities, org, user).userId();
    }

    private UUID orgId(String org) {
        return TestLogin.login(identities, org, "bootstrap").orgId();
    }

    private <T> T runAs(String user, Supplier<T> action) {
        return TestLogin.runAs(identities, uni, user, action);
    }

    private <T> T inOrg(String org, Supplier<T> action) {
        UUID previous = OrgContext.set(orgId(org));
        try {
            return tx.execute(status -> action.get());
        } finally {
            OrgContext.set(previous);
        }
    }

    private void member(UUID course, String owner, String user, String role) throws Exception {
        userId(uni, user);
        mvc.perform(put("/core/api/courses/" + course + "/members").with(as(owner)).contentType(APPLICATION_JSON)
                        .content("{\"externalId\":\"" + user + "\",\"role\":\"" + role + "\"}"))
                .andExpect(status().isOk());
    }

    /** Moves the user's last login into the past, as if they had stayed away (owner role: no RLS). */
    private static void lastSeen(UUID userId, Instant at) throws SQLException {
        try (Connection c = DriverManager.getConnection(TestDatabase.container().getJdbcUrl(), "hestia_owner",
                "test-owner"); var update = c.prepareStatement("update core.app_user set last_seen_at = ? where id = ?")) {
            update.setTimestamp(1, Timestamp.from(at));
            update.setObject(2, userId);
            assertThat(update.executeUpdate()).isEqualTo(1);
        }
    }

    /** The uploader of a course's slide template, {@code "<null>"} if unknown, {@code null} if there is none. */
    private static String templateOwner(UUID course) throws SQLException {
        try (Connection c = DriverManager.getConnection(TestDatabase.container().getJdbcUrl(), "hestia_owner",
                "test-owner"); var q = c.prepareStatement("select owner_id from teaching.slide_templates where id = ?")) {
            q.setString(1, course.toString());
            try (ResultSet rs = q.executeQuery()) {
                if (!rs.next()) return null;
                String owner = rs.getString(1);
                return owner == null ? "<null>" : owner;
            }
        }
    }

    private boolean exists(String org, UUID userId) {
        return inOrg(org, () -> users.findById(userId).isPresent());
    }

    private List<Integer> warningsOf(String org, UUID userId) {
        return inOrg(org, () -> warnings.findAll().stream().filter(w -> w.getUserId().equals(userId))
                .map(InactivityWarning::getAfterDays).sorted().toList());
    }

    @Test
    void warnsTwiceThenDeletesTheAccountWithItsSoloCoursesAndKeepsSharedOnes() throws Exception {
        UUID alice = userId(uni, "alice");
        UUID solo = runAs("alice", () -> courseApi.create("Solo").id());
        UUID shared = runAs("alice", () -> courseApi.create("Shared").id());
        UUID personal = runAs("alice", () -> courseApi.personalCourse().id());
        member(shared, "alice", "carol", "VIEWER");
        member(shared, "alice", "bob", "EDITOR");
        member(personal, "alice", "dave", "VIEWER");
        runAs("alice", () -> uploadConfirmation.confirm(UploadConfirmationService.NOTICE_VERSION));
        var pptx = new ByteArrayOutputStream();
        try (var show = new XMLSlideShow()) {
            show.write(pptx);
        }
        var template = new MockMultipartFile("template", "template.pptx", "application/octet-stream", pptx.toByteArray());
        mvc.perform(multipart("/workshopper/api/workshop/courses/" + shared + "/template").file(template).with(as("alice")))
                .andExpect(status().isOk());
        assertThat(templateOwner(shared)).isEqualTo(alice.toString());

        String json = mvc.perform(post("/examlense/api/exams").with(as("alice")).contentType(APPLICATION_JSON)
                        .content("{\"title\":\"t\",\"source\":\"manual\",\"course_id\":\"" + solo + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID examId = UUID.fromString(JsonPath.read(json, "$.id"));
        String pdf = orgId(uni) + "/" + examId + "/source.pdf";
        storage.store("exam-pdfs", pdf, new byte[] {1});
        inOrg(uni, () -> {
            var exam = exams.findById(examId).orElseThrow();
            exam.setSourceFileUrl(pdf);
            return exams.save(exam);
        });

        Instant start = Instant.now();
        lastSeen(alice, start.minus(Duration.ofDays(301)));

        sweep.run(start);
        assertThat(warningsOf(uni, alice)).containsExactly(300);
        sweep.run(start.plus(Duration.ofDays(1)));
        assertThat(warningsOf(uni, alice)).as("one warning per threshold").containsExactly(300);

        sweep.run(start.plus(Duration.ofDays(50))); // 351 days
        assertThat(warningsOf(uni, alice)).containsExactly(300, 350);

        sweep.run(start.plus(Duration.ofDays(64))); // 365 days, but the last warning is only 14 days old
        assertThat(exists(uni, alice)).isTrue();

        sweep.run(start.plus(Duration.ofDays(65)));
        assertThat(exists(uni, alice)).isFalse();

        // Solo course gone with its exam and stored file.
        assertThat(inOrg(uni, () -> exams.findById(examId))).isEmpty();
        assertThat(storage.download("exam-pdfs", pdf)).isNull();
        assertThat(runAs("bob", () -> courseApi.listMine(0, 100)).stream().map(c -> c.id()).toList())
                .contains(shared).doesNotContain(solo);

        // Shared course kept; the longest-standing editor became owner, the viewer stayed viewer.
        assertThat(runAs("bob", () -> courseApi.get(shared).role().name())).isEqualTo("OWNER");
        assertThat(runAs("carol", () -> courseApi.get(shared).role().name())).isEqualTo("VIEWER");
        // The template alice uploaded stays with the course; its uploader is no longer known.
        assertThat(templateOwner(shared)).isEqualTo("<null>");

        // The personal course another member shared became an ordinary course of that member.
        var former = runAs("dave", () -> courseApi.get(personal));
        assertThat(former.role().name()).isEqualTo("OWNER");
        assertThat(former.personal()).isFalse();

        // Warnings and the upload confirmation went with the account.
        assertThat(warningsOf(uni, alice)).isEmpty();
        try (Connection c = DriverManager.getConnection(TestDatabase.container().getJdbcUrl(), "hestia_owner",
                "test-owner"); var q = c.prepareStatement("select count(*) from core.upload_confirmation where user_id = ?")) {
            q.setObject(1, alice);
            try (ResultSet rs = q.executeQuery()) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }
        }
    }

    @Test
    void aLoginResetsThePeriodAndShowsTheWarningOnce() throws Exception {
        UUID eve = userId(uni, "eve");
        Instant start = Instant.now();
        lastSeen(eve, start.minus(Duration.ofDays(360)));
        sweep.run(start);
        assertThat(warningsOf(uni, eve)).containsExactly(350);

        // eve logs in (the request's login moves last_seen_at): a new period begins.
        mvc.perform(get("/core/api/privacy/inactivity-notices").with(as("eve")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].afterDays").value(350))
                .andExpect(jsonPath("$[0].deleteAfterDays").value(365));

        sweep.run(start.plus(Duration.ofDays(30)));
        assertThat(exists(uni, eve)).isTrue();
        assertThat(warningsOf(uni, eve)).as("no new warning in the new period").containsExactly(350);

        mvc.perform(post("/core/api/privacy/inactivity-notices/acknowledge").with(as("eve")))
                .andExpect(status().isOk());
        mvc.perform(get("/core/api/privacy/inactivity-notices").with(as("eve")))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void nobodyIsDeletedWithoutTheLastWarning() throws Exception {
        UUID frank = userId(uni, "frank");
        Instant start = Instant.now();
        lastSeen(frank, start.minus(Duration.ofDays(400))); // the job was off for a long time
        sweep.run(start);
        assertThat(exists(uni, frank)).isTrue();
        assertThat(warningsOf(uni, frank)).containsExactly(350);
        sweep.run(start.plus(Duration.ofDays(15)));
        assertThat(exists(uni, frank)).isFalse();
    }

    @Test
    void organisationsStayApart() throws Exception {
        UUID inactiveA = userId(uni, "grace");
        UUID activeB = userId(otherUni, "grace"); // same external id, another university
        Instant start = Instant.now();
        lastSeen(inactiveA, start.minus(Duration.ofDays(320)));
        sweep.run(start);

        assertThat(warningsOf(uni, inactiveA)).containsExactly(300);
        assertThat(warningsOf(otherUni, activeB)).isEmpty();
        // The warning is invisible to the other organisation, also in the database.
        assertThat(inOrg(otherUni, () -> warnings.findAll().stream().map(InactivityWarning::getUserId).toList()))
                .doesNotContain(inactiveA);
        try (Connection c = DriverManager.getConnection(TestDatabase.container().getJdbcUrl(), "hestia_app",
                "test-app")) {
            try (var set = c.prepareStatement("select set_config('hestia.org_id', ?, false)")) {
                set.setString(1, orgId(otherUni).toString());
                set.execute();
            }
            try (var q = c.prepareStatement("select count(*) from core.inactivity_warning where user_id = ?")) {
                q.setObject(1, inactiveA);
                try (ResultSet rs = q.executeQuery()) {
                    rs.next();
                    assertThat(rs.getInt(1)).isZero();
                }
            }
        }
        // grace of B can still see nothing of A's notices.
        mvc.perform(get("/core/api/privacy/inactivity-notices").with(TestLogin.as(identities, otherUni, "grace")))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void aDryRunOnlyLogs() throws Exception {
        UUID henry = userId(uni, "henry");
        UUID course = runAs("henry", () -> courseApi.create("Kept").id());
        Instant start = Instant.now();
        lastSeen(henry, start.minus(Duration.ofDays(500)));
        InactivitySweep dryRun = new InactivitySweep(
                new InactivityProperties(true, true, List.of(300, 350), 365, "log"), organisations, users, warnings,
                courseRelease, notifier, tx, dataSource);

        InactivitySweep.Report report = dryRun.run(start);

        assertThat(report.dryRun()).isTrue();
        assertThat(report.warned()).isPositive();
        assertThat(exists(uni, henry)).isTrue();
        assertThat(warningsOf(uni, henry)).isEmpty();
        assertThat(runAs("henry", () -> courseApi.get(course).title())).isEqualTo("Kept");
    }

    @Test
    void onlyOneRunAtATime() throws Exception {
        try (Connection c = DriverManager.getConnection(TestDatabase.container().getJdbcUrl(), "hestia_app",
                "test-app"); var lock = c.prepareStatement("select pg_advisory_lock(?)")) {
            lock.setLong(1, InactivitySweep.LOCK_KEY);
            lock.execute();
            assertThat(sweep.run(Instant.now()).skipped()).isTrue();
        }
        assertThat(sweep.run(Instant.now()).skipped()).isFalse();
    }
}
