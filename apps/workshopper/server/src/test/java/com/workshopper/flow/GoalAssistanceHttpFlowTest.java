package com.workshopper.flow;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** AI-assisted steps, answered by the fake model (no real key, no network), and the LearningGoalHub import. */
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
    void goalImport_passesLearningGoalHubAnswerThrough() {
        FAKE_LEARNING_GOAL_HUB.respondWith("{\"content\":[{\"id\":7,\"title\":\"Fake LGH Course\"}],\"totalElements\":1}");

        FlowResponse response = signedIn().get(FlowPaths.LGH_COURSES);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().path("content").get(0).path("title").asText()).isEqualTo("Fake LGH Course");
        assertThat(FAKE_LEARNING_GOAL_HUB.requests).anyMatch(r -> r.startsWith("GET ") && r.contains("courses"));
    }
}
