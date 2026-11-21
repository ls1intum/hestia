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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The learning goals of Workshopper sessions live in core: a plan keeps goal ids only, text comes
 * from core when it is read, one alignment link connects each goal with its session, and a session
 * without a course goes into the personal course and can be moved.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Teaching: sessions marked for review when a goal changes")
class TeachingGoalReviewIT {

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
    @DisplayName("a changed goal marks the session's plan entry and summary; confirming clears it; the review list names the session")
    void changedGoalMarksSession() throws Exception {
        UUID course = asAlice(() -> courses.create("Algorithms").id());
        GoalInfo g = goal(course, "Original wording");
        String session = saveSession(null, course, List.of(g));
        assertThat(plan(session).get(0).has("review")).isFalse();

        asAlice(() -> goals.update(g.id(), g.asChange().withText("New wording")));

        JsonNode entry = plan(session).get(0);
        assertThat(entry.path("review").asText()).isEqualTo("GOAL_CHANGED");
        String linkId = entry.path("reviewLinkId").asText();
        assertThat(linkId).isNotBlank();
        assertThat(openReviews(session)).isEqualTo(1);

        JsonNode reviews = json.readTree(mvc.perform(get("/core/api/courses/" + course + "/reviews").with(alice()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(reviews).hasSize(1);
        assertThat(reviews.get(0).path("targetLabel").asText()).isEqualTo("Session");
        assertThat(reviews.get(0).path("targetId").asText()).isEqualTo(session);

        assertThat(entry.path("reviewGoalVersion").asInt()).isEqualTo(2);
        mvc.perform(confirm(course, linkId, 2)).andExpect(status().is2xxSuccessful());
        assertThat(plan(session).get(0).has("review")).isFalse();
        assertThat(openReviews(session)).isZero();
    }

    @Test
    @DisplayName("saving a session keeps the review of a removed goal until it is confirmed")
    void savingKeepsReviewOfRemovedGoal() throws Exception {
        UUID course = asAlice(() -> courses.create("Algorithms").id());
        GoalInfo kept = goal(course, "Kept goal");
        GoalInfo dropped = goal(course, "Goal that will be removed");
        String session = saveSession(null, course, List.of(kept, dropped));

        asAlice(() -> {
            goals.remove(dropped.id());
            return null;
        });
        // The resolved plan no longer holds the removed goal, so the client saves the plan without it
        assertThat(plan(session)).hasSize(1);
        saveSession(session, course, List.of(kept));

        assertThat(linkedGoals(session)).containsExactlyInAnyOrder(kept.id(), dropped.id());
        JsonNode reviews = json.readTree(mvc.perform(get("/core/api/courses/" + course + "/reviews").with(alice()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(reviews).hasSize(1);
        assertThat(reviews.get(0).path("reason").asText()).isEqualTo("GOAL_REMOVED");
        assertThat(openReviews(session)).isEqualTo(1);

        // Once confirmed, the next save removes the link of the goal that left the plan
        mvc.perform(confirm(course, reviews.get(0).path("linkId").asText(), reviews.get(0).path("goalVersion").asInt()))
                .andExpect(status().isOk());
        saveSession(session, course, List.of(kept));
        assertThat(linkedGoals(session)).containsExactly(kept.id());
    }

    private org.springframework.test.web.servlet.RequestBuilder confirm(UUID course, String linkId, int goalVersion) {
        return post("/core/api/courses/" + course + "/reviews/" + linkId + "/confirm").with(alice()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"goalVersion\":" + goalVersion + "}");
    }

    private int openReviews(String session) throws Exception {
        JsonNode list = json.readTree(mvc.perform(get(API + "/sessions").with(alice()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        for (JsonNode s : list) if (session.equals(s.path("id").asText())) return s.path("openReviews").asInt();
        throw new AssertionError("session not listed");
    }

    private List<UUID> linkedGoals(String session) {
        return asAlice(() -> alignment.goalsFor(DomainModule.TEACHING, "SESSION", session)).stream()
                .map(AlignmentLinkInfo::goalId).toList();
    }
}
