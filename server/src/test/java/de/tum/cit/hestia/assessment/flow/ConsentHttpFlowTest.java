package de.tum.cit.hestia.assessment.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The external models (all of ExamLense's parsers and solvers) need the user's consent. Without it a
 * parse and a solve are refused with 428 {@code CONSENT_REQUIRED} and nothing reaches the provider;
 * granting through {@code /api/ai-consent} lets the same start through, revoking refuses it again.
 */
@Tag("http-flow")
class ConsentHttpFlowTest extends AbstractHttpFlowTest {

    @Test
    void parseAndSolveNeedConsent_grantAllowsThem_revokeRefusesThemAgain() {
        // Setup: an uploaded exam PDF to parse, and an exam in `evaluating` with a task to solve.
        String parseExam = http.postJson(FlowPaths.api("/exams"), Map.of(
            "title", "Consent parse", "source", "pdf", "status", "parsing")).json().get("id").asText();
        String storagePath = http.postFile(FlowPaths.api("/exams/" + parseExam + "/pdf"),
            "exam.pdf", "application/pdf", FlowFixtures.tinyExamPdf()).json().get("storage_path").asText();

        String solveExam = http.postJson(FlowPaths.api("/exams"), Map.of(
            "title", "Consent solve", "source", "manual", "status", "evaluating")).json().get("id").asText();
        assertThat(http.postJson(FlowPaths.api("/tasks"), Map.of(
            "exam_id", solveExam, "position", 1, "type", "single_choice",
            "prompt", "Which keyword declares a constant in Java?", "points", 2,
            "options", List.of(
                Map.of("id", "11111111-1111-1111-1111-111111111111", "text", "final", "is_correct", true),
                Map.of("id", "22222222-2222-2222-2222-222222222222", "text", "var", "is_correct", false))))
            .status()).isEqualTo(200);

        // 1. Without consent: both refused, the provider sees nothing, nothing changed on the exams.
        assertThat(http.delete(FlowPaths.api("/ai-consent")).json().get("granted").asBoolean()).isFalse();
        int before = FAKE_MODEL.requestPaths().size();

        assertConsentRequired(startParse(parseExam, storagePath));
        assertConsentRequired(startSolve(solveExam));

        assertThat(FAKE_MODEL.requestPaths()).as("no request reached the provider").hasSize(before);
        assertThat(http.get(FlowPaths.api("/exams/" + parseExam)).json().get("status").asText()).isEqualTo("parsing");
        assertThat(http.get(FlowPaths.api("/exams/" + solveExam)).json().get("status").asText()).isEqualTo("evaluating");

        // 2. After granting, the same starts are accepted and run to the end.
        grantConsent();
        assertThat(startParse(parseExam, storagePath).status()).isEqualTo(202);
        assertThat(http.pollUntil(FlowPaths.api("/exams/" + parseExam),
            e -> !"parsing".equals(e.get("status").asText()), Duration.ofSeconds(60))
            .json().get("status").asText()).isEqualTo("draft");
        FlowHttp.Reply solve = startSolve(solveExam);
        assertThat(solve.status()).as("solve-exam: %s", solve.raw()).isEqualTo(200);
        assertThat(http.pollUntil(FlowPaths.api("/exams/" + solveExam),
            e -> !"evaluating".equals(e.get("status").asText()), Duration.ofSeconds(60))
            .json().get("status").asText()).isEqualTo("grading");
        int afterRuns = FAKE_MODEL.requestPaths().size();
        assertThat(afterRuns).isGreaterThan(before);

        // 3. After revoking, they are refused again and nothing more is sent.
        assertThat(http.delete(FlowPaths.api("/ai-consent")).json().get("granted").asBoolean()).isFalse();
        assertConsentRequired(startParse(parseExam, storagePath));
        assertConsentRequired(startSolve(solveExam));
        assertThat(FAKE_MODEL.requestPaths()).hasSize(afterRuns);
    }

    private FlowHttp.Reply startParse(String examId, String storagePath) {
        return http.postJson(FlowPaths.api("/parse-exam-pdf"), Map.of("exam_id", examId, "storage_path", storagePath));
    }

    private FlowHttp.Reply startSolve(String examId) {
        return http.postJson(FlowPaths.api("/solve-exam"), Map.of("exam_id", examId));
    }

    private static void assertConsentRequired(FlowHttp.Reply reply) {
        assertThat(reply.status()).as("reply: %s", reply.raw()).isEqualTo(428);
        assertThat(reply.json().get("code").asText()).isEqualTo("CONSENT_REQUIRED");
    }
}
