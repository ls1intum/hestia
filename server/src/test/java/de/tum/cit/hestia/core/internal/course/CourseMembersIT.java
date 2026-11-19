package de.tum.cit.hestia.core.internal.course;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.tum.cit.hestia.TestDatabase;
import de.tum.cit.hestia.core.CourseApi;
import de.tum.cit.hestia.core.CourseRole;
import de.tum.cit.hestia.core.TestLogin;
import de.tum.cit.hestia.core.internal.identity.HestiaAuthentication;
import de.tum.cit.hestia.core.internal.identity.HestiaPrincipal;
import de.tum.cit.hestia.core.internal.identity.IdentityService;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Identity, organisations and course roles of {@code core}: one filter chain, 401 without login,
 * members managed by owners only, another organisation sees nothing (also in the database).
 */
@SpringBootTest
@AutoConfigureMockMvc
class CourseMembersIT {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @MockitoBean
    RelyingPartyRegistrationRepository relyingPartyRegistrationRepository;

    @Autowired MockMvc mvc;
    @Autowired IdentityService identities;
    @Autowired CourseApi courseApi;
    @Autowired ApplicationContext context;

    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final String uniA = "uni-a-" + run + ".test";
    private final String uniB = "uni-b-" + run + ".test";

    private UUID courseOfAlice() {
        return TestLogin.runAs(identities, uniA, "alice", () -> courseApi.create("Algorithms").id());
    }

    private static String members(UUID courseId) {
        return "/core/api/courses/" + courseId + "/members";
    }

    @Test
    void thereIsExactlyOneSecurityFilterChain() {
        assertThat(context.getBeansOfType(SecurityFilterChain.class)).hasSize(1);
    }

    @Test
    void withoutLoginTheAnswerIs401() throws Exception {
        mvc.perform(get(members(UUID.randomUUID()))).andExpect(status().isUnauthorized());
        mvc.perform(get("/learninggoalhub/api/courses")).andExpect(status().isUnauthorized());
        mvc.perform(get("/workshopper/api/courses")).andExpect(status().isUnauthorized());
        mvc.perform(get("/examlense/api/exams")).andExpect(status().isUnauthorized());
    }

    @Test
    void healthStaysOpen() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void writesWithoutCsrfTokenAreRefused() throws Exception {
        UUID course = courseOfAlice();
        HestiaPrincipal alice = TestLogin.login(identities, uniA, "alice");
        mvc.perform(put(members(course)).with(authentication(new HestiaAuthentication(alice)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"externalId\":\"alice\",\"role\":\"OWNER\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void ownerManagesMembersOthersDoNot() throws Exception {
        UUID course = courseOfAlice();
        TestLogin.login(identities, uniA, "bob");

        mvc.perform(get(members(course)).with(TestLogin.as(identities, uniA, "alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].externalId").value("alice"))
                .andExpect(jsonPath("$[0].role").value("OWNER"));

        // Not a member: the course does not exist for bob.
        mvc.perform(get(members(course)).with(TestLogin.as(identities, uniA, "bob")))
                .andExpect(status().isNotFound());

        mvc.perform(put(members(course)).with(TestLogin.as(identities, uniA, "alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"externalId\":\"bob\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("VIEWER"));

        // A viewer reads the course but manages nothing.
        assertThat(TestLogin.runAs(identities, uniA, "bob", () -> courseApi.get(course).role()))
                .isEqualTo(CourseRole.VIEWER);
        mvc.perform(get(members(course)).with(TestLogin.as(identities, uniA, "bob")))
                .andExpect(status().isForbidden());

        // The last owner cannot leave.
        UUID aliceId = TestLogin.login(identities, uniA, "alice").userId();
        mvc.perform(delete(members(course) + "/" + aliceId).with(TestLogin.as(identities, uniA, "alice")))
                .andExpect(status().isConflict());
    }

    /**
     * Two owners demote each other at the same moment: the course row lock lets only one win, so
     * the course keeps an owner. Repeated, since without the lock the race is not certain to hit.
     */
    @Test
    void twoOwnersDemotingEachOtherKeepAnOwner() throws Exception {
        for (int round = 0; round < 5; round++) {
            UUID course = courseOfAlice();
            TestLogin.login(identities, uniA, "bob");
            mvc.perform(put(members(course)).with(TestLogin.as(identities, uniA, "alice"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"externalId\":\"bob\",\"role\":\"OWNER\"}"))
                    .andExpect(status().isOk());

            var start = new java.util.concurrent.CyclicBarrier(2);
            var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                var aliceDemotesBob = pool.submit(() -> demote(start, course, "alice", "bob"));
                var bobDemotesAlice = pool.submit(() -> demote(start, course, "bob", "alice"));
                java.util.List<Integer> codes = java.util.List.of(aliceDemotesBob.get(), bobDemotesAlice.get());
                // The loser is no longer an owner once it gets the lock.
                assertThat(codes).containsExactlyInAnyOrder(200, 403);
            } finally {
                pool.shutdownNow();
            }
            assertThat(countOwners(course)).isEqualTo(1);
        }
    }

    private int demote(java.util.concurrent.CyclicBarrier start, UUID course, String actor, String target)
            throws Exception {
        start.await();
        return mvc.perform(put(members(course)).with(TestLogin.as(identities, uniA, actor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"externalId\":\"" + target + "\",\"role\":\"VIEWER\"}"))
                .andReturn().getResponse().getStatus();
    }

    @Autowired CourseMembershipRepository membershipRows;

    private long countOwners(UUID course) {
        de.tum.cit.hestia.core.OrgContext.set(TestLogin.login(identities, uniA, "alice").orgId());
        try {
            return membershipRows.countByCourseIdAndRole(course, CourseRole.OWNER);
        } finally {
            de.tum.cit.hestia.core.OrgContext.set(null);
        }
    }

    @Test
    void anotherOrganisationSeesNothing() throws Exception {
        UUID course = courseOfAlice();
        // Same external id, other university: a different user.
        TestLogin.login(identities, uniB, "bob");

        mvc.perform(get(members(course)).with(TestLogin.as(identities, uniB, "alice")))
                .andExpect(status().isNotFound());
        assertThat(TestLogin.runAs(identities, uniB, "alice", () -> courseApi.listMine(0, 50))).isEmpty();

        // A member must have logged in in the same organisation.
        mvc.perform(put(members(course)).with(TestLogin.as(identities, uniA, "alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"externalId\":\"bob\",\"role\":\"EDITOR\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rowLevelSecurityHidesOtherOrganisationsInTheDatabase() throws Exception {
        UUID course = courseOfAlice();
        UUID orgA = TestLogin.login(identities, uniA, "alice").orgId();
        UUID orgB = TestLogin.login(identities, uniB, "carol").orgId();
        var pg = TestDatabase.container();
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), "hestia_app", "test-app")) {
            assertThat(count(c, null, course)).isZero();
            assertThat(count(c, orgB, course)).isZero();
            assertThat(count(c, orgA, course)).isEqualTo(1);
        }
    }

    private static int count(Connection c, UUID org, UUID course) throws Exception {
        try (var set = c.prepareStatement("select set_config('hestia.org_id', ?, false)")) {
            set.setString(1, org == null ? "" : org.toString());
            set.execute();
        }
        try (var q = c.prepareStatement("select count(*) from core.course where id = ?")) {
            q.setObject(1, course);
            try (ResultSet rs = q.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
