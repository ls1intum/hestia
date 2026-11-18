package de.tum.cit.hestia.materials.flow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The core flow: create a course, upload lecture PDFs, extract learning goals, fetch them. */
@Tag("http-flow")
class LearningGoalExtractionHttpFlowTest extends HttpFlowTestBase {

    @Test
    void uploadedDocumentsAreExtractedIntoLearningGoalsThatCanBeFetched() {
        long courseId = createCourse("Flow test: algorithms");

        HubApi.Response upload = uploadLectures(courseId);
        assertThat(upload.status()).as(upload.raw()).isEqualTo(201);
        assertThat(upload.json().size()).isEqualTo(2);
        List<String> uploaded = new ArrayList<>();
        upload.json().forEach(document -> uploaded.add(document.path("filename").asText()));
        assertThat(uploaded).containsExactlyInAnyOrder("sorting.pdf", "searching.pdf");

        HubApi.Response documents = api().get("/courses/" + courseId + "/documents");
        assertThat(documents.status()).isEqualTo(200);
        assertThat(documents.json().size()).isEqualTo(2);

        // Before the extraction there are no goals.
        assertThat(goalsOf(courseId, "").size()).isZero();

        extractAndWait(courseId);

        JsonNode all = goalsOf(courseId, "");
        assertThat(all.size()).isGreaterThan(0);
        assertThat(texts(all)).anySatisfy(
                text -> assertThat(text).contains("Implementing merge sort on an array of integers"));
        assertThat(texts(all)).anySatisfy(
                text -> assertThat(text).contains("Applying binary search to a sorted array"));
        all.forEach(goal -> {
            assertThat(goal.path("id").asLong()).isPositive();
            assertThat(goal.path("text").asText()).isNotBlank();
            // Goals outside the skill/knowledge tiers (e.g. synthesized parents) carry no role.
            if (!goal.path("role").isNull() && !goal.path("role").isMissingNode()) {
                assertThat(goal.path("role").asText()).isIn("SKILL", "KNOWLEDGE");
            }
        });
        long withRole = 0;
        for (JsonNode goal : all) {
            if (goal.hasNonNull("role")) {
                withRole++;
            }
        }

        // The tier filter separates skills from the knowledge items under them.
        JsonNode skills = goalsOf(courseId, "&role=SKILL");
        JsonNode knowledge = goalsOf(courseId, "&role=KNOWLEDGE");
        assertThat(skills.size()).isGreaterThan(0);
        assertThat(knowledge.size()).isGreaterThan(0);
        assertThat((long) skills.size() + knowledge.size()).isEqualTo(withRole);
        assertThat(texts(knowledge)).allSatisfy(text -> assertThat(text).startsWith("Recalling the terms behind"));

        // The course summary reflects the finished extraction.
        HubApi.Response summary = api().get("/courses/" + courseId);
        assertThat(summary.status()).isEqualTo(200);
        assertThat(summary.json().path("documentCount").asLong()).isEqualTo(2);
        assertThat(summary.json().path("goalCount").asLong()).isEqualTo(all.size());
        assertThat(summary.json().path("extractionStatus").asText()).isEqualTo("SUCCEEDED");

        // Every model call was a prompt the fake knew; an unknown one would have answered 400.
        assertThat(MODEL.calls()).doesNotContain("unknown").contains("extraction");
    }
}
