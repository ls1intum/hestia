package de.tum.cit.hestia.teaching.flow;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** AI-assisted steps, answered by the fake model (no real key, no network), and the goals of a course. */
@Tag("http-flow")
@ActiveProfiles(FlowAuth.SIGNED_IN_PROFILE)
class GoalAssistanceHttpFlowTest extends FlowTestSupport {

    @Test
    void refineGoal_returnsTheModelsSuggestions() {
        modelAnswers("[{\"type\":\"refine\",\"values\":[\"Students can explain recursion with an example\"],"
                + "\"message\":\"Make the goal observable\"}]");

        FlowResponse response = signedIn().post(FlowPaths.REFINE_GOAL,
                Map.of("goal", "Understand recursion", "context", Map.of("sessionType", "workshop")));

        assertThat(response.status()).isEqualTo(200);
        JsonNode suggestions = response.json();
        assertThat(suggestions).hasSize(1);
        assertThat(suggestions.get(0).path("type").asText()).isEqualTo("refine");
        assertThat(suggestions.get(0).path("values").get(0).asText())
                .isEqualTo("Students can explain recursion with an example");
        assertThat(suggestions.get(0).path("message").asText()).isEqualTo("Make the goal observable");
        assertThat(FAKE_MODEL.requests).hasSize(1);
    }

    @Test
    void fixGoalsGrammar_returnsTheModelsCorrection() {
        modelAnswers("[\"Students can explain recursion.\"]");

        FlowResponse response = signedIn().post(FlowPaths.FIX_GOALS_GRAMMAR, List.of("students can explan recursion"));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json()).hasSize(1);
        assertThat(response.json().get(0).asText()).isEqualTo("Students can explain recursion.");
        assertThat(FAKE_MODEL.requests).hasSize(1);
    }

    @Test
    void goalPicker_listsTheCoursesCoreGoals_andGoalsAreSavedThroughThePlanEndpoint() {
        FlowClient api = signedIn();
        String courseId = api.post(FlowPaths.COURSE_DRAFT,
                Map.of("title", "Goal Course", "currentStep", "settings", "draftStateJson", "{}")).json().path("id").asText();

        FlowResponse saved = api.put(FlowPaths.courseGoals(courseId),
                List.of(Map.of("text", "Students can explain recursion")));
        assertThat(saved.status()).isEqualTo(200);
        String goalId = saved.json().get(0).path("id").asText();
        assertThat(java.util.UUID.fromString(goalId)).isNotNull();

        FlowResponse listed = api.get(FlowPaths.courseGoals(courseId));
        assertThat(listed.status()).isEqualTo(200);
        assertThat(listed.json()).hasSize(1);
        assertThat(listed.json().get(0).path("id").asText()).isEqualTo(goalId);
        assertThat(listed.json().get(0).path("text").asText()).isEqualTo("Students can explain recursion");

        // The goal is edited by id: same goal, new wording
        FlowResponse edited = api.put(FlowPaths.courseGoals(courseId),
                List.of(Map.of("id", goalId, "text", "Students can trace recursion")));
        assertThat(edited.json().get(0).path("id").asText()).isEqualTo(goalId);
        assertThat(api.get(FlowPaths.courseGoals(courseId)).json().get(0).path("text").asText())
                .isEqualTo("Students can trace recursion");

        api.delete(FlowPaths.course(courseId));
    }
}
