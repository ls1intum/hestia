package de.tum.cit.hestia.materials.flow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import de.tum.cit.hestia.materials.MaterialsTestBase;
import de.tum.cit.hestia.materials.MaterialsTestConfiguration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The application under test, without a model: the whole server on a random port against the
 * Testcontainers Postgres, plus the HTTP helpers. Which model answers is decided by the subclass:
 * {@link HttpFlowTestBase} wires the fake, {@link LogosSmokeLiveIT} the real gateway. (A subclass
 * cannot re-point a property its superclass already registered, so neither of them does.)
 */
@Import({MaterialsTestConfiguration.class, FlowLoginConfiguration.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class FlowApplication extends MaterialsTestBase {

    @DynamicPropertySource
    static void application(DynamicPropertyRegistry registry) {
        // One skill per 40 characters, so the short fixture lines may each become a skill.
        registry.add("hestia.extraction.skill-target-chars", () -> "40");
        registry.add("hestia.extraction.exercise-skill-target-chars", () -> "40");
    }

    @Value("${local.server.port}")
    int port;

    HubApi api() {
        return HubApi.loggedIn(port);
    }

    HubApi anonymousApi() {
        return HubApi.anonymous(port);
    }

    /** Creates a course and returns its id; fails the test when the server does not answer 201. */
    long createCourse(String name) {
        HubApi.Response created = api().postJson("/courses", Map.of("name", name));
        assertThat(created.status()).as(created.raw()).isEqualTo(201);
        return created.json().path("id").asLong();
    }

    /** Uploads the two synthetic lectures and returns the response. */
    HubApi.Response uploadLectures(long courseId) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("sorting.pdf", TestPdfs.textPdf(TestPdfs.SORTING_LECTURE));
        files.put("searching.pdf", TestPdfs.textPdf(TestPdfs.SEARCHING_LECTURE));
        return api().upload("/courses/" + courseId + "/documents", files, "application/pdf");
    }

    /** Starts the extraction and waits until it has finished; fails the test if it did not succeed. */
    void extractAndWait(long courseId) {
        extractAndWait(courseId, 90_000);
    }

    /** As {@link #extractAndWait(long)}, with a longer wait for a real model. */
    void extractAndWait(long courseId, long timeoutMillis) {
        HubApi.Response started = api().post("/courses/" + courseId + "/extract");
        assertThat(started.status()).as(started.raw()).isEqualTo(202);

        long deadline = System.currentTimeMillis() + timeoutMillis;
        String status = "";
        while (System.currentTimeMillis() < deadline) {
            HubApi.Response progress = api().get("/courses/" + courseId + "/extract/status");
            if (progress.status() == 200) {
                status = progress.json().path("status").asText();
                assertThat(status).as("extraction failed: " + progress.raw()).isNotEqualTo("FAILED");
                if (status.equals("SUCCEEDED")) {
                    return;
                }
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("The extraction did not finish in time; last status: " + status);
    }

    /** The goals of a course (the "content" list of the paged response); query is e.g. "&role=SKILL". */
    JsonNode goalsOf(long courseId, String query) {
        HubApi.Response goals = api().get("/courses/" + courseId + "/learning-goals?size=200" + query);
        assertThat(goals.status()).as(goals.raw()).isEqualTo(200);
        return goals.json().path("content");
    }

    static List<String> texts(JsonNode goals) {
        List<String> texts = new ArrayList<>();
        goals.forEach(goal -> texts.add(goal.path("text").asText()));
        return texts;
    }
}
