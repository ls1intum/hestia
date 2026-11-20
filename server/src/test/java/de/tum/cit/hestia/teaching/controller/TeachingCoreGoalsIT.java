package de.tum.cit.hestia.teaching.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.tum.cit.hestia.TestDatabase;
import de.tum.cit.hestia.core.AlignmentApi;
import de.tum.cit.hestia.core.AlignmentLinkInfo;
import de.tum.cit.hestia.core.CourseApi;
import de.tum.cit.hestia.core.DomainModule;
import de.tum.cit.hestia.core.GoalInfo;
import de.tum.cit.hestia.core.GoalOrigin;
import de.tum.cit.hestia.core.LearningGoalApi;
import de.tum.cit.hestia.core.NewGoal;
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

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The learning goals of Workshopper sessions live in core: a plan keeps goal ids only, text comes
 * from core when it is read, one alignment link connects each goal with its session, and a session
 * without a course goes into the personal course and can be moved.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Teaching: goals from core, links and moving a session")
class TeachingCoreGoalsIT {

    private static final String API = "/workshopper/api/workshop";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @MockitoBean
    RelyingPartyRegistrationRepository relyingPartyRegistrationRepository;

    @Autowired MockMvc mvc;
    @Autowired IdentityService identities;
    @Autowired CourseApi courses;
    @Autowired LearningGoalApi goals;
    @Autowired AlignmentApi alignment;
    @Autowired de.tum.cit.hestia.teaching.repository.WorkshopSessionRepository sessions;

    private final ObjectMapper json = new ObjectMapper();
    private final String uni = "teach-goals-" + UUID.randomUUID().toString().substring(0, 8) + ".test";

    private RequestPostProcessor alice() {
        return TestLogin.as(identities, uni, "alice");
    }

    private <T> T asAlice(Supplier<T> action) {
        return TestLogin.runAs(identities, uni, "alice", action);
    }

    private GoalInfo goal(UUID course, String text) {
        return asAlice(() -> goals.create(course, NewGoal.of(text, GoalOrigin.MANUAL, DomainModule.TEACHING)));
    }

    private String saveSession(String sessionId, UUID course, List<GoalInfo> plan) throws Exception {
        StringBuilder entries = new StringBuilder();
        for (GoalInfo g : plan) {
            if (entries.length() > 0) entries.append(',');
            // What the client sends: id plus text and levels, which the server must not store
            entries.append("{\"id\":\"").append(g.id()).append("\",\"goal\":\"stale text\",\"bloomLevel\":\"APPLY\","
                    + "\"prerequisites\":[\"p\"],\"achieveActivities\":[],\"assessActivities\":[],\"session\":\"S1\"}");
        }
        String draft = "{\"refinedGoals\":[" + entries + "],\"workshopInput\":{\"duration\":90}}";
        var body = json.createObjectNode().put("title", "Session").put("currentStep", "goals").put("draftStateJson", draft);
        if (sessionId != null) body.put("sessionId", sessionId);
        if (course != null) body.put("courseId", course.toString());
        String answer = mvc.perform(post(API + "/sessions/draft").with(alice()).contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(answer).path("id").asText();
    }

    private JsonNode plan(String sessionId) throws Exception {
        JsonNode detail = json.readTree(mvc.perform(get(API + "/sessions/" + sessionId).with(alice()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        return json.readTree(detail.path("draftStateJson").asText()).path("refinedGoals");
    }

    @Test
    @DisplayName("a goal renamed in core shows its new text when the session is read, without any import")
    void renamedGoalShowsInThePlan() throws Exception {
        UUID course = asAlice(() -> courses.create("Algorithms").id());
        GoalInfo g = goal(course, "Original wording");
        String session = saveSession(null, course, List.of(g));

        assertThat(plan(session).get(0).path("goal").asText()).isEqualTo("Original wording");

        asAlice(() -> goals.update(g.id(), g.asChange().withText("New wording")));

        JsonNode entry = plan(session).get(0);
        assertThat(entry.path("id").asText()).isEqualTo(g.id().toString());
        assertThat(entry.path("goal").asText()).isEqualTo("New wording");
        assertThat(entry.path("originalGoal").asText()).isEqualTo("Original wording");
        assertThat(entry.path("prerequisites")).hasSize(1);
        assertThat(entry.path("session").asText()).isEqualTo("S1");

        // The stored plan holds the id and Workshopper's own fields only
        String stored = asAlice(() -> sessions.findById(session).orElseThrow().getDraftStateJson());
        assertThat(stored).doesNotContain("stale text").doesNotContain("bloomLevel").contains(g.id().toString());
    }

    @Test
    @DisplayName("a plan holds one SESSION link per goal; a goal leaving the plan loses its link; delete removes all")
    void linksFollowThePlan() throws Exception {
        UUID course = asAlice(() -> courses.create("Algorithms").id());
        GoalInfo a = goal(course, "Goal A");
        GoalInfo b = goal(course, "Goal B");
        String session = saveSession(null, course, List.of(a, b));
        assertThat(linkedGoals(session)).containsExactlyInAnyOrder(a.id(), b.id());

        saveSession(session, null, List.of(b));
        assertThat(linkedGoals(session)).containsExactly(b.id());

        // A goal of another course is refused
        UUID other = asAlice(() -> courses.create("Other").id());
        GoalInfo foreign = goal(other, "Foreign");
        mvc.perform(post(API + "/sessions/draft").with(alice()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"" + session + "\",\"draftStateJson\":\"{\\\"refinedGoals\\\":[{\\\"id\\\":\\\""
                                + foreign.id() + "\\\"}]}\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(delete(API + "/sessions/" + session).with(alice())).andExpect(status().isNoContent());
        assertThat(linkedGoals(session)).isEmpty();
    }

    @Test
    @DisplayName("a session without a course lands in the personal course; moving it removes the links to the old course's goals only")
    void personalCourseAndMove() throws Exception {
        UUID personal = asAlice(() -> courses.personalCourse().id());
        String session = saveSession(null, null, List.of());
        assertThat(json.readTree(mvc.perform(get(API + "/sessions/" + session).with(alice())).andReturn()
                .getResponse().getContentAsString()).path("courseId").asText()).isEqualTo(personal.toString());

        GoalInfo inPersonal = goal(personal, "Personal goal");
        UUID target = asAlice(() -> courses.create("Target").id());
        GoalInfo inTarget = goal(target, "Target goal");
        saveSession(session, null, List.of(inPersonal));
        // A link to a goal of the target course, made directly, is none of the old course's
        asAlice(() -> alignment.link(inTarget.id(), DomainModule.TEACHING, "SESSION", session));
        assertThat(linkedGoals(session)).containsExactlyInAnyOrder(inPersonal.id(), inTarget.id());

        mvc.perform(put(API + "/sessions/" + session + "/course").with(alice()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"courseId\":\"" + target + "\"}"))
                .andExpect(status().isOk());

        assertThat(linkedGoals(session)).containsExactly(inTarget.id());
        assertThat(json.readTree(mvc.perform(get(API + "/sessions/" + session).with(alice())).andReturn()
                .getResponse().getContentAsString()).path("courseId").asText()).isEqualTo(target.toString());
        // The goal of the old course is gone from the session's plan, and the goal itself is untouched
        assertThat(plan(session)).isEmpty();
        assertThat(asAlice(() -> goals.goal(inPersonal.id()).removed())).isFalse();
    }

    private List<UUID> linkedGoals(String session) {
        return asAlice(() -> alignment.goalsFor(DomainModule.TEACHING, "SESSION", session)).stream()
                .map(AlignmentLinkInfo::goalId).toList();
    }
}
