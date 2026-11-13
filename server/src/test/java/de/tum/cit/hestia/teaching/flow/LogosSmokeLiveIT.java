package de.tum.cit.hestia.teaching.flow;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test against the real Logos gateway: the AI steps of {@link GoalAssistanceHttpFlowTest},
 * but answered by Logos. It checks only the shape of the answer (the prompt
 * still yields JSON the server can parse), never the wording, so a model update does not break it.
 *
 * <p>Gated on {@code RUN_LIVE_LOGOS=1}, so it never runs in the normal build. Run with:
 * <pre>
 * RUN_LIVE_LOGOS=1 LOGOS_API_KEY=... ./gradlew :apps:workshopper:server:test --tests '*LogosSmokeLiveIT'
 * </pre>
 * Optional: {@code LOGOS_BASE_URL} (default: the TUM AET gateway).
 */
@Tag("logos-live")
@EnabledIfEnvironmentVariable(named = "RUN_LIVE_LOGOS", matches = "1")
@ActiveProfiles(FlowAuth.SIGNED_IN_PROFILE)
class LogosSmokeLiveIT extends FlowApplication {

    @DynamicPropertySource
    static void realModel(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.base-url",
                () -> System.getenv().getOrDefault("LOGOS_BASE_URL", "https://logos.aet.cit.tum.de"));
        registry.add("spring.ai.openai.api-key", () -> System.getenv("LOGOS_API_KEY"));
        registry.add("spring.ai.retry.max-attempts", () -> "3");
    }

    @Test
    void realModelRefinesAGoal() {
        assertThat(System.getenv("LOGOS_API_KEY")).as("LOGOS_API_KEY must be set").isNotBlank();

        FlowResponse response = signedIn().post(FlowPaths.REFINE_GOAL,
                Map.of("goal", "Understand recursion", "context", Map.of("sessionType", "workshop")));

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(response.json().isArray()).isTrue();
    }

    @Test
    void realModelFixesGoalGrammar() {
        assertThat(System.getenv("LOGOS_API_KEY")).as("LOGOS_API_KEY must be set").isNotBlank();

        FlowResponse response = signedIn().post(FlowPaths.FIX_GOALS_GRAMMAR,
                List.of("students can explan recursion"));

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(response.json()).hasSize(1);
        // The endpoint returns the input unchanged when the model call fails, so only a corrected
        // typo proves that the model answered.
        assertThat(response.json().get(0).asText()).containsIgnoringCase("explain");
    }
}
