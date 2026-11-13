package de.tum.cit.hestia.materials.flow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Smoke test against the real Logos gateway: the extraction flow of
 * {@link LearningGoalExtractionHttpFlowTest}, but every model call goes to Logos. It checks only
 * what any working model must produce (the run succeeds, goals exist, each has a text), never the
 * wording, so a model update does not break it.
 *
 * <p>Gated on {@code RUN_LIVE_LOGOS=1}, so it never runs in the normal build. Run with:
 * <pre>
 * RUN_LIVE_LOGOS=1 LOGOS_API_KEY=... ./gradlew :server:test --tests '*LogosSmokeLiveIT'
 * </pre>
 * Optional: {@code LOGOS_BASE_URL} (default: the TUM AET gateway).
 */
@Tag("logos-live")
@EnabledIfEnvironmentVariable(named = "RUN_LIVE_LOGOS", matches = "1")
class LogosSmokeLiveIT extends FlowApplication {

    @DynamicPropertySource
    static void realModel(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.base-url",
                () -> System.getenv().getOrDefault("LOGOS_BASE_URL", "https://logos.aet.cit.tum.de"));
        registry.add("spring.ai.openai.api-key", () -> System.getenv("LOGOS_API_KEY"));
    }

    @Test
    void realModelExtractsLearningGoalsFromAnUploadedLecture() {
        assertThat(System.getenv("LOGOS_API_KEY")).as("LOGOS_API_KEY must be set").isNotBlank();

        long courseId = createCourse("Logos smoke test");
        HubApi.Response upload = uploadLectures(courseId);
        assertThat(upload.status()).as(upload.raw()).isBetween(200, 299);

        extractAndWait(courseId, 600_000);

        JsonNode goals = goalsOf(courseId, "");
        assertThat(goals.size()).as("goals extracted by the real model").isPositive();
        goals.forEach(goal -> assertThat(goal.path("text").asText()).isNotBlank());
    }
}
