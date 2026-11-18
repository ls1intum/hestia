package de.tum.cit.hestia.assessment.flow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** AI step: solve single tasks with a canned model answer and read the answers back. */
@Tag("http-flow")
class SolveTaskHttpFlowTest extends AbstractHttpFlowTest {

    @Test
    void solveTasks_storesCannedAnswers() {
        String examId = http.postJson(FlowPaths.api("/exams"), Map.of(
            "title", "Solve Flow Exam", "source", "manual")).json().get("id").asText();

        String textTaskId = http.postJson(FlowPaths.api("/tasks"), Map.of(
            "exam_id", examId, "position", 1, "type", "text",
            "prompt", "Explain what an HTTP status code is.", "points", 4)).json().get("id").asText();
        FlowHttp.Reply choice = http.postJson(FlowPaths.api("/tasks"), Map.of(
            "exam_id", examId, "position", 2, "type", "single_choice",
            "prompt", "Which keyword declares a constant in Java?", "points", 2,
            "options", List.of(
                Map.of("id", "11111111-1111-1111-1111-111111111111", "text", "final", "is_correct", true),
                Map.of("id", "22222222-2222-2222-2222-222222222222", "text", "var", "is_correct", false))));
        assertThat(choice.status()).isEqualTo(200);
        String choiceTaskId = choice.json().get("id").asText();
        String firstOptionId = choice.json().get("options").get(0).get("id").asText();

        assertSolveOk(textTaskId);
        assertSolveOk(choiceTaskId);

        FlowHttp.Reply answers = http.get(FlowPaths.api("/exams/" + examId + "/answers"));
        assertThat(answers.status()).isEqualTo(200);
        assertThat(answers.json()).hasSize(2);

        JsonNode textAnswer = answerFor(answers.json(), textTaskId);
        assertThat(textAnswer.get("answer_text").asText()).isEqualTo(FakeModelServer.SOLVED_TEXT_ANSWER);
        assertThat(textAnswer.get("model").asText()).startsWith("fake-");

        JsonNode choiceAnswer = answerFor(answers.json(), choiceTaskId);
        assertThat(choiceAnswer.get("selected_option_ids")).hasSize(1);
        assertThat(choiceAnswer.get("selected_option_ids").get(0).asText()).isEqualTo(firstOptionId);
    }

    @Test
    void solveUnknownTask_is404() {
        FlowHttp.Reply res = http.postJson(FlowPaths.api("/solve-task"),
            Map.of("task_id", "00000000-0000-0000-0000-000000000000"));
        assertThat(res.status()).isEqualTo(404);
    }

    private void assertSolveOk(String taskId) {
        FlowHttp.Reply res = http.postJson(FlowPaths.api("/solve-task"), Map.of("task_id", taskId));
        assertThat(res.status()).as("solve-task: %s", res.raw()).isEqualTo(200);
        assertThat(res.json().get("ok").asBoolean()).isTrue();
    }

    private static JsonNode answerFor(JsonNode answers, String taskId) {
        for (JsonNode a : answers) {
            if (taskId.equals(a.get("task_id").asText())) return a;
        }
        throw new AssertionError("no answer for task " + taskId + " in " + answers);
    }
}
