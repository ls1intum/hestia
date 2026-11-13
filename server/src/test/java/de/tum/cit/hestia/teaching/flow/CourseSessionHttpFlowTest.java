package de.tum.cit.hestia.teaching.flow;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Core flow: create a course, create a session in it, read it back, list, update, move, delete. */
@Tag("http-flow")
@ActiveProfiles(FlowAuth.SIGNED_IN_PROFILE)
class CourseSessionHttpFlowTest extends FlowTestSupport {

    private static final String DRAFT_STATE =
            "{\"workshopInput\":{\"duration\":90,\"participants\":20,\"sessionType\":\"workshop\"}}";

    private String createCourse(FlowClient api, String title) {
        FlowResponse created = api.post(FlowPaths.COURSE_DRAFT,
                Map.of("title", title, "currentStep", "settings", "draftStateJson", "{}"));
        assertThat(created.status()).isEqualTo(200);
        String id = created.json().path("id").asText();
        assertThat(id).isNotBlank();
        return id;
    }

    private String createSession(FlowClient api, String title, String courseId) {
        FlowResponse created = api.post(FlowPaths.SESSION_DRAFT,
                Map.of("title", title, "currentStep", "goals", "courseId", courseId, "draftStateJson", DRAFT_STATE));
        assertThat(created.status()).isEqualTo(200);
        String id = created.json().path("id").asText();
        assertThat(id).isNotBlank();
        return id;
    }

    @Test
    void createCourse_createSessionInIt_readItBackAndList() throws Exception {
        FlowClient api = signedIn();

        String courseId = createCourse(api, "Flow Course A");
        String sessionId = createSession(api, "Flow Session A", courseId);

        FlowResponse course = api.get(FlowPaths.course(courseId));
        assertThat(course.status()).isEqualTo(200);
        assertThat(course.json().path("id").asText()).isEqualTo(courseId);
        assertThat(course.json().path("title").asText()).isEqualTo("Flow Course A");

        FlowResponse session = api.get(FlowPaths.session(sessionId));
        assertThat(session.status()).isEqualTo(200);
        JsonNode body = session.json();
        assertThat(body.path("id").asText()).isEqualTo(sessionId);
        assertThat(body.path("title").asText()).isEqualTo("Flow Session A");
        assertThat(body.path("courseId").asText()).isEqualTo(courseId);
        assertThat(body.path("status").asText()).isEqualTo("draft");
        assertThat(body.path("currentStep").asText()).isEqualTo("goals");
        // Stored as jsonb, so compare the parsed JSON, not its formatting.
        JsonNode draftState = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(body.path("draftStateJson").asText());
        assertThat(draftState.path("workshopInput").path("duration").asInt()).isEqualTo(90);

        JsonNode sessions = api.get(FlowPaths.SESSIONS).json();
        JsonNode listed = find(sessions, sessionId);
        assertThat(listed).as("session in list").isNotNull();
        assertThat(listed.path("courseId").asText()).isEqualTo(courseId);
        assertThat(find(api.get(FlowPaths.COURSES).json(), courseId)).as("course in list").isNotNull();

        api.delete(FlowPaths.session(sessionId));
        api.delete(FlowPaths.course(courseId));
    }

    @Test
    void updateRenameMoveAndDeleteSession() {
        FlowClient api = signedIn();
        String courseId = createCourse(api, "Flow Course B");
        String sessionId = createSession(api, "Before", courseId);

        // update the draft (same id) -> same session, new step and title
        FlowResponse updated = api.post(FlowPaths.SESSION_DRAFT,
                Map.of("sessionId", sessionId, "title", "After", "currentStep", "skeleton", "draftStateJson", DRAFT_STATE));
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.json().path("id").asText()).isEqualTo(sessionId);
        JsonNode read = api.get(FlowPaths.session(sessionId)).json();
        assertThat(read.path("title").asText()).isEqualTo("After");
        assertThat(read.path("currentStep").asText()).isEqualTo("skeleton");
        assertThat(read.path("courseId").asText()).isEqualTo(courseId);

        // rename
        assertThat(api.put(FlowPaths.sessionAction(sessionId, "rename"), Map.of("title", "Renamed")).status()).isEqualTo(200);
        assertThat(api.get(FlowPaths.session(sessionId)).json().path("title").asText()).isEqualTo("Renamed");
        assertThat(api.put(FlowPaths.sessionAction(sessionId, "rename"), Map.of("title", " ")).status()).isEqualTo(400);

        // move back to top level
        assertThat(api.put(FlowPaths.sessionAction(sessionId, "move"), Map.of("courseId", "")).status()).isEqualTo(200);
        assertThat(api.get(FlowPaths.session(sessionId)).json().path("courseId").isNull()
                || api.get(FlowPaths.session(sessionId)).json().path("courseId").asText().isEmpty()).isTrue();

        // delete -> gone
        assertThat(api.delete(FlowPaths.session(sessionId)).status()).isEqualTo(204);
        assertThat(api.get(FlowPaths.session(sessionId)).status()).isEqualTo(404);
        assertThat(api.delete(FlowPaths.course(courseId)).status()).isEqualTo(204);
    }

    @Test
    void unknownCourseAndSessionAreNotFound_andSessionCannotJoinUnknownCourse() {
        FlowClient api = signedIn();
        assertThat(api.get(FlowPaths.course("does-not-exist")).status()).isEqualTo(404);
        assertThat(api.get(FlowPaths.session("does-not-exist")).status()).isEqualTo(404);
        FlowResponse response = api.post(FlowPaths.SESSION_DRAFT,
                Map.of("title", "Orphan", "courseId", "does-not-exist", "draftStateJson", "{}"));
        assertThat(response.status()).isEqualTo(404);
    }

    private static JsonNode find(JsonNode array, String id) {
        for (JsonNode item : array) {
            if (id.equals(item.path("id").asText())) return item;
        }
        return null;
    }
}
