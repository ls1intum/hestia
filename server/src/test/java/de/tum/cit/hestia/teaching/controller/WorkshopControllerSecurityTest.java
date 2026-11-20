package de.tum.cit.hestia.teaching.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.tum.cit.hestia.TestDatabase;
import de.tum.cit.hestia.core.CourseApi;
import de.tum.cit.hestia.core.CourseNotFoundException;
import de.tum.cit.hestia.core.TestLogin;
import de.tum.cit.hestia.core.internal.identity.IdentityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Login, organisation and course roles of the teaching endpoints, against the real filter chain and
 * database: 401 without login, another organisation sees nothing, a VIEWER cannot write, a session
 * without a course lands in the creator's personal course.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Teaching endpoints: login, organisation and course roles")
class WorkshopControllerSecurityTest {

    private static final String API = "/workshopper/api/workshop";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @MockitoBean
    RelyingPartyRegistrationRepository relyingPartyRegistrationRepository;

    @Autowired MockMvc mvc;
    @Autowired IdentityService identities;
    @Autowired CourseApi courseApi;

    private final ObjectMapper json = new ObjectMapper();
    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final String uniA = "uni-a-" + run + ".test";
    private final String uniB = "uni-b-" + run + ".test";

    private RequestPostProcessor as(String org, String user) {
        return TestLogin.as(identities, org, user);
    }

    private String createCourse(RequestPostProcessor who, String title) throws Exception {
        String body = mvc.perform(post(API + "/courses/draft").with(who).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"currentStep\":\"settings\",\"draftStateJson\":\"{}\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("id").asText();
    }

    private String createSession(RequestPostProcessor who, String courseId) throws Exception {
        String course = courseId == null ? "" : ",\"courseId\":\"" + courseId + "\"";
        String body = mvc.perform(post(API + "/sessions/draft").with(who).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Session\",\"currentStep\":\"goals\",\"draftStateJson\":\"{}\"" + course + "}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("id").asText();
    }

    private UUID coreIdOf(String org, String user, String courseId) {
        return UUID.fromString(courseId); // a Workshopper course is the core course
    }

    private void addMember(String course, String member, String role) throws Exception {
        TestLogin.login(identities, uniA, member);
        mvc.perform(put("/core/api/courses/" + coreIdOf(uniA, "alice", course) + "/members").with(as(uniA, "alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"externalId\":\"" + member + "\",\"role\":\"" + role + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("without login every endpoint answers 401")
    void withoutLoginTheAnswerIs401() throws Exception {
        mvc.perform(get(API + "/sessions")).andExpect(status().isUnauthorized());
        mvc.perform(get(API + "/courses")).andExpect(status().isUnauthorized());
        mvc.perform(get(API + "/sessions/some-id")).andExpect(status().isUnauthorized());
        mvc.perform(post(API + "/session").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(API + "/sessions/draft").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(put(API + "/sessions/some-id/rename").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"hacked\"}")).andExpect(status().isUnauthorized());
        mvc.perform(delete(API + "/sessions/some-id").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(put(API + "/sessions/some-id/course").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"courseId\":\"" + UUID.randomUUID() + "\"}")).andExpect(status().isUnauthorized());
        mvc.perform(get(API + "/courses/" + UUID.randomUUID() + "/goals")).andExpect(status().isUnauthorized());
        mvc.perform(put(API + "/courses/" + UUID.randomUUID() + "/goals").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("[]")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("health stays open")
    void healthIsOpen() throws Exception {
        mvc.perform(get(API + "/health")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a write without CSRF token is refused")
    void writesNeedTheCsrfToken() throws Exception {
        var auth = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .authentication(new de.tum.cit.hestia.core.internal.identity.HestiaAuthentication(
                        TestLogin.login(identities, uniA, "alice")));
        mvc.perform(post(API + "/courses/draft").with(auth).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a user of another organisation sees nothing")
    void anotherOrganisationSeesNothing() throws Exception {
        String course = createCourse(as(uniA, "alice"), "Algorithms");
        String session = createSession(as(uniA, "alice"), course);
        String privateSession = createSession(as(uniA, "alice"), null);
        TestLogin.login(identities, uniB, "alice"); // same external id, other university: another user
        var other = as(uniB, "alice");

        mvc.perform(get(API + "/courses").with(other)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get(API + "/sessions").with(other)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get(API + "/courses/" + course).with(other)).andExpect(status().isNotFound());
        mvc.perform(get(API + "/sessions/" + session).with(other)).andExpect(status().isNotFound());
        mvc.perform(get(API + "/sessions/" + privateSession).with(other)).andExpect(status().isNotFound());
        mvc.perform(delete(API + "/courses/" + course).with(other)).andExpect(status().isNotFound());
        mvc.perform(put(API + "/sessions/" + session + "/rename").with(other).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"x\"}")).andExpect(status().isNotFound());
        mvc.perform(get(API + "/export/course/" + course + "/zip").with(other)).andExpect(status().isNotFound());
        mvc.perform(get(API + "/courses/" + course + "/goals").with(other)).andExpect(status().isNotFound());
        mvc.perform(put(API + "/courses/" + course + "/goals").with(other).contentType(MediaType.APPLICATION_JSON)
                .content("[{\"text\":\"x\"}]")).andExpect(status().isNotFound());
        String otherOrgsCourse = createCourse(other, "Theirs");
        mvc.perform(put(API + "/sessions/" + session + "/course").with(other).contentType(MediaType.APPLICATION_JSON)
                .content("{\"courseId\":\"" + otherOrgsCourse + "\"}")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a non-member of the same organisation sees nothing of a course")
    void nonMemberSeesNothing() throws Exception {
        String course = createCourse(as(uniA, "alice"), "Algorithms");
        String session = createSession(as(uniA, "alice"), course);
        TestLogin.login(identities, uniA, "bob");
        var bob = as(uniA, "bob");

        mvc.perform(get(API + "/courses").with(bob)).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get(API + "/sessions").with(bob)).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get(API + "/courses/" + course).with(bob)).andExpect(status().isNotFound());
        mvc.perform(get(API + "/sessions/" + session).with(bob)).andExpect(status().isNotFound());
        // ... and cannot put a session into the course
        mvc.perform(post(API + "/sessions/draft").with(bob).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"x\",\"courseId\":\"" + course + "\"}")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a VIEWER reads but cannot write; an EDITOR writes but cannot delete the course; the OWNER deletes it")
    void rolesDecideWhatAUserMayDo() throws Exception {
        String course = createCourse(as(uniA, "alice"), "Algorithms");
        String session = createSession(as(uniA, "alice"), course);
        addMember(course, "vera", "VIEWER");
        addMember(course, "edgar", "EDITOR");
        var viewer = as(uniA, "vera");
        var editor = as(uniA, "edgar");

        // VIEWER: reads
        mvc.perform(get(API + "/courses").with(viewer)).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get(API + "/courses/" + course).with(viewer)).andExpect(status().isOk());
        mvc.perform(get(API + "/sessions").with(viewer)).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get(API + "/sessions/" + session).with(viewer)).andExpect(status().isOk());

        // VIEWER: cannot write
        String draft = "{\"sessionId\":\"" + session + "\",\"title\":\"changed\",\"currentStep\":\"goals\"}";
        mvc.perform(post(API + "/sessions/draft").with(viewer).contentType(MediaType.APPLICATION_JSON).content(draft))
                .andExpect(status().isForbidden());
        mvc.perform(post(API + "/sessions/draft").with(viewer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"new\",\"courseId\":\"" + course + "\"}")).andExpect(status().isForbidden());
        mvc.perform(put(API + "/sessions/" + session + "/rename").with(viewer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"x\"}")).andExpect(status().isForbidden());
        mvc.perform(put(API + "/sessions/" + session + "/finish").with(viewer)).andExpect(status().isForbidden());
        mvc.perform(delete(API + "/sessions/" + session).with(viewer)).andExpect(status().isForbidden());
        mvc.perform(post(API + "/courses/draft").with(viewer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionId\":\"" + course + "\",\"title\":\"x\"}")).andExpect(status().isForbidden());
        mvc.perform(put(API + "/courses/" + course + "/rename").with(viewer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"x\"}")).andExpect(status().isForbidden());
        mvc.perform(delete(API + "/courses/" + course).with(viewer)).andExpect(status().isForbidden());
        mvc.perform(delete(API + "/courses/" + course + "/template").with(viewer)).andExpect(status().isForbidden());
        mvc.perform(put(API + "/courses/" + course + "/goals").with(viewer).contentType(MediaType.APPLICATION_JSON)
                .content("[{\"text\":\"A goal the viewer wrote\"}]")).andExpect(status().isForbidden());
        mvc.perform(get(API + "/courses/" + course + "/goals").with(viewer)).andExpect(status().isOk());
        // ... nor move a session out of the course, or into it
        String ownCourse = createCourse(viewer, "Veras own course");
        mvc.perform(put(API + "/sessions/" + session + "/course").with(viewer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"courseId\":\"" + ownCourse + "\"}")).andExpect(status().isForbidden());
        String veraSession = createSession(viewer, ownCourse);
        mvc.perform(put(API + "/sessions/" + veraSession + "/course").with(viewer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"courseId\":\"" + course + "\"}")).andExpect(status().isForbidden());

        // EDITOR: writes, renames, but does not delete the course
        mvc.perform(post(API + "/sessions/draft").with(editor).contentType(MediaType.APPLICATION_JSON).content(draft))
                .andExpect(status().isOk());
        mvc.perform(put(API + "/courses/" + course + "/rename").with(editor).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Renamed\"}")).andExpect(status().isOk());
        mvc.perform(get(API + "/courses/" + course).with(viewer)).andExpect(jsonPath("$.title").value("Renamed"));
        mvc.perform(delete(API + "/courses/" + course).with(editor)).andExpect(status().isForbidden());

        // OWNER: deletes the teaching course together with the core course
        UUID core = coreIdOf(uniA, "alice", course);
        mvc.perform(delete(API + "/courses/" + course).with(as(uniA, "alice"))).andExpect(status().isNoContent());
        mvc.perform(get(API + "/sessions/" + session).with(as(uniA, "alice"))).andExpect(status().isNotFound());
        assertThatThrownBy(() -> TestLogin.runAs(identities, uniA, "alice", () -> courseApi.get(core)))
                .isInstanceOf(CourseNotFoundException.class);
    }

    @Test
    @DisplayName("a session without a course lands in the personal course and is visible to its creator only")
    void sessionWithoutCourseIsPrivate() throws Exception {
        String session = createSession(as(uniA, "alice"), null);
        TestLogin.login(identities, uniA, "bob");

        var detail = json.readTree(mvc.perform(get(API + "/sessions/" + session).with(as(uniA, "alice")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(detail.path("personalCourse").asBoolean()).isTrue();
        UUID personal = TestLogin.runAs(identities, uniA, "alice", () -> courseApi.personalCourse().id());
        assertThat(detail.path("courseId").asText()).isEqualTo(personal.toString());
        mvc.perform(get(API + "/sessions/" + session).with(as(uniA, "bob"))).andExpect(status().isNotFound());
        mvc.perform(delete(API + "/sessions/" + session).with(as(uniA, "bob"))).andExpect(status().isNotFound());
        mvc.perform(get(API + "/sessions").with(as(uniA, "bob"))).andExpect(jsonPath("$.length()").value(0));
        assertThat(mvc.perform(get(API + "/sessions").with(as(uniA, "alice"))).andReturn().getResponse()
                .getContentAsString()).contains(session);
    }
}
