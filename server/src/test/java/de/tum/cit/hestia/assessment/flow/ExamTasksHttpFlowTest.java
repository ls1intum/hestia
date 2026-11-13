package de.tum.cit.hestia.assessment.flow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Create an exam, add tasks by hand, read them back, see the exam in the list. */
@Tag("http-flow")
class ExamTasksHttpFlowTest extends AbstractHttpFlowTest {

    @Test
    void createExam_addTasks_fetchTasksAndList() {
        FlowHttp.Reply created = http.postJson(FlowPaths.api("/exams"), Map.of(
            "title", "Manual Flow Exam", "course", "Flow Testing 101", "source", "manual"));
        assertThat(created.status()).isEqualTo(200);
        String examId = created.json().get("id").asText();
        assertThat(created.json().get("title").asText()).isEqualTo("Manual Flow Exam");
        assertThat(created.json().get("status").asText()).isEqualTo("draft");

        FlowHttp.Reply t1 = http.postJson(FlowPaths.api("/tasks"), Map.of(
            "exam_id", examId, "position", 1, "type", "single_choice",
            "prompt", "Pick the even number.", "points", 2,
            "options", List.of(
                Map.of("id", "o1", "text", "3", "is_correct", false),
                Map.of("id", "o2", "text", "4", "is_correct", true))));
        FlowHttp.Reply t2 = http.postJson(FlowPaths.api("/tasks"), Map.of(
            "exam_id", examId, "position", 2, "type", "text",
            "prompt", "Define idempotence.", "points", 3));
        assertThat(t1.status()).isEqualTo(200);
        assertThat(t2.status()).isEqualTo(200);

        FlowHttp.Reply tasks = http.get(FlowPaths.api("/exams/" + examId + "/tasks"));
        assertThat(tasks.status()).isEqualTo(200);
        assertThat(tasks.json().isArray()).isTrue();
        assertThat(tasks.json()).hasSize(2);
        assertThat(tasks.json().get(0).get("prompt").asText()).isEqualTo("Pick the even number.");
        assertThat(tasks.json().get(0).get("type").asText()).isEqualTo("single_choice");
        assertThat(tasks.json().get(0).get("options")).hasSize(2);
        assertThat(tasks.json().get(1).get("prompt").asText()).isEqualTo("Define idempotence.");
        assertThat(tasks.json().get(1).get("exam_id").asText()).isEqualTo(examId);

        FlowHttp.Reply list = http.get(FlowPaths.api("/exams"));
        assertThat(list.status()).isEqualTo(200);
        JsonNode mine = null;
        for (JsonNode e : list.json()) {
            if (examId.equals(e.get("id").asText())) mine = e;
        }
        assertThat(mine).as("created exam appears in the list").isNotNull();
        assertThat(mine.get("task_count").asLong()).isEqualTo(2);
    }
}
