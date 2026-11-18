package de.tum.cit.hestia.materials.flow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Create, list, read, rename and delete a course. */
@Tag("http-flow")
class CourseLifecycleHttpFlowTest extends HttpFlowTestBase {

    @Test
    void aCourseCanBeCreatedListedRenamedAndDeleted() {
        HubApi.Response created = api().postJson("/courses", Map.of("name", "Flow test: lifecycle"));
        assertThat(created.status()).as(created.raw()).isEqualTo(201);
        long id = created.json().path("id").asLong();
        assertThat(id).isPositive();
        assertThat(created.json().path("name").asText()).isEqualTo("Flow test: lifecycle");

        HubApi.Response list = api().get("/courses?size=200");
        assertThat(list.status()).isEqualTo(200);
        boolean listed = false;
        for (JsonNode course : list.json().path("content")) {
            listed |= course.path("id").asLong() == id;
        }
        assertThat(listed).as("the new course is in the list").isTrue();

        HubApi.Response read = api().get("/courses/" + id);
        assertThat(read.status()).isEqualTo(200);
        assertThat(read.json().path("name").asText()).isEqualTo("Flow test: lifecycle");
        assertThat(read.json().path("documentCount").asLong()).isZero();
        assertThat(read.json().path("goalCount").asLong()).isZero();

        HubApi.Response renamed = api().patchJson("/courses/" + id, Map.of("name", "Flow test: renamed"));
        assertThat(renamed.status()).as(renamed.raw()).isEqualTo(200);
        assertThat(api().get("/courses/" + id).json().path("name").asText()).isEqualTo("Flow test: renamed");

        assertThat(api().delete("/courses/" + id).status()).isEqualTo(204);
        HubApi.Response gone = api().get("/courses/" + id);
        assertThat(gone.status()).isEqualTo(404);
        assertThat(gone.json().path("code").asText()).isEqualTo("NOT_FOUND");
    }

    @Test
    void aCourseWithoutANameIsRejected() {
        HubApi.Response rejected = api().postJson("/courses", Map.of("name", " "));
        assertThat(rejected.status()).isEqualTo(400);
    }
}
