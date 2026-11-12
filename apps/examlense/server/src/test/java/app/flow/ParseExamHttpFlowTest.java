package app.flow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Create an exam, upload a PDF, parse it (canned model answer), fetch the parsed tasks. */
@Tag("http-flow")
class ParseExamHttpFlowTest extends AbstractHttpFlowTest {

    @Test
    void uploadPdf_parse_thenTasksAreFetchable() {
        int modelCallsBefore = FAKE_MODEL.requestPaths().size();

        FlowHttp.Reply created = http.postJson(FlowPaths.api("/exams"), Map.of(
            "title", "Upload", "source", "pdf", "status", "parsing"));
        assertThat(created.status()).isEqualTo(200);
        String examId = created.json().get("id").asText();

        FlowHttp.Reply upload = http.postFile(FlowPaths.api("/exams/" + examId + "/pdf"),
            "exam.pdf", "application/pdf", FlowFixtures.tinyExamPdf());
        assertThat(upload.status()).isEqualTo(200);
        String storagePath = upload.json().get("storage_path").asText();
        assertThat(storagePath).isNotBlank();

        FlowHttp.Reply accepted = http.postJson(FlowPaths.api("/parse-exam-pdf"),
            Map.of("exam_id", examId, "storage_path", storagePath));
        assertThat(accepted.status()).isEqualTo(202);
        assertThat(accepted.json().get("exam_id").asText()).isEqualTo(examId);

        FlowHttp.Reply exam = http.pollUntil(FlowPaths.api("/exams/" + examId),
            e -> !"parsing".equals(e.get("status").asText()), Duration.ofSeconds(60));
        assertThat(exam.json().get("status").asText())
            .as("exam status after parse, parse_error=%s", exam.json().path("parse_error").asText())
            .isEqualTo("draft");
        assertThat(exam.json().get("title").asText()).isEqualTo(FakeModelServer.EXAM_TITLE);
        assertThat(exam.json().get("course").asText()).isEqualTo(FakeModelServer.EXAM_COURSE);

        FlowHttp.Reply tasks = http.get(FlowPaths.api("/exams/" + examId + "/tasks"));
        assertThat(tasks.status()).isEqualTo(200);
        assertThat(tasks.json()).hasSize(3);
        assertThat(tasks.json().get(0).get("type").asText()).isEqualTo("single_choice");
        assertThat(tasks.json().get(0).get("prompt").asText())
            .isEqualTo("Which keyword declares a constant in Java?");
        JsonNode firstOptions = tasks.json().get(0).get("options");
        assertThat(firstOptions).hasSize(3);
        assertThat(firstOptions.get(0).get("text").asText()).isEqualTo("final");
        assertThat(tasks.json().get(1).get("type").asText()).isEqualTo("multiple_choice");
        assertThat(tasks.json().get(2).get("type").asText()).isEqualTo("text");

        assertThat(FAKE_MODEL.requestPaths().size())
            .as("the parse went through the fake model endpoint")
            .isGreaterThan(modelCallsBefore);
    }

    @Test
    void parseWithoutCredential_isRejected() {
        FlowHttp.Reply res = http.postJsonUnauthenticated(FlowPaths.api("/parse-exam-pdf"),
            Map.of("exam_id", "00000000-0000-0000-0000-000000000000", "storage_path", "x"));
        assertThat(res.status()).isEqualTo(401);
    }
}
