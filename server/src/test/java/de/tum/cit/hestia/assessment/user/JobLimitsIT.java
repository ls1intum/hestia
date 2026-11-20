package de.tum.cit.hestia.assessment.user;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import de.tum.cit.hestia.assessment.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The per-user job limits of the ai module, seen through the ExamLense endpoints. The point of
 * limiting per user is that one person exhausting theirs leaves everyone else unaffected. (Blank
 * provider keys: the background runs fail before anything is sent, which is irrelevant here.)
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "hestia.ai.job-limits.exam-parse=2", "hestia.ai.job-limits.exam-solve=1",
    "hestia.ai.openai.api-key=", "hestia.ai.anthropic.api-key=", "hestia.ai.gemini.api-key=",
})
class JobLimitsIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;

    private void consent(String user) throws Exception {
        mvc.perform(post("/examlense/api/ai-consent").with(as(user))).andExpect(status().isOk());
    }

    private String createExam(String user, String examStatus, String source) throws Exception {
        String body = mvc.perform(post("/examlense/api/exams").with(as(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Limits\",\"source\":\"" + source + "\",\"status\":\"" + examStatus + "\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private ResultActions startParse(String user, String examId) throws Exception {
        return mvc.perform(post("/examlense/api/parse-exam-pdf").with(as(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"exam_id\":\"" + examId + "\",\"storage_path\":\"missing.pdf\"}"));
    }

    private ResultActions startSolve(String user, String examId) throws Exception {
        return mvc.perform(post("/examlense/api/solve-exam").with(as(user))
            .contentType(MediaType.APPLICATION_JSON).content("{\"exam_id\":\"" + examId + "\"}"));
    }

    @Test
    void parsesUpToTheLimitAreAcceptedAndTheNextIsRefusedWith429() throws Exception {
        consent("p-heavy");
        String exam = createExam("p-heavy", "parsing", "pdf");

        startParse("p-heavy", exam).andExpect(status().isAccepted());
        startParse("p-heavy", exam).andExpect(status().isAccepted());
        startParse("p-heavy", exam).andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("JOB_LIMIT_EXCEEDED"));

        mvc.perform(get("/examlense/api/me").with(as("p-heavy")))
            .andExpect(jsonPath("$.quota.parse_limit").value(2))
            .andExpect(jsonPath("$.quota.parse_used").value(2))
            .andExpect(jsonPath("$.quota.parse_remaining").value(0));
    }

    @Test
    void oneUserExhaustingTheirLimitDoesNotAffectAnother() throws Exception {
        consent("o-heavy");
        consent("o-light");
        String heavyExam = createExam("o-heavy", "parsing", "pdf");
        String lightExam = createExam("o-light", "parsing", "pdf");
        startParse("o-heavy", heavyExam).andExpect(status().isAccepted());
        startParse("o-heavy", heavyExam).andExpect(status().isAccepted());
        startParse("o-heavy", heavyExam).andExpect(status().isTooManyRequests());

        startParse("o-light", lightExam).andExpect(status().isAccepted());
    }

    @Test
    void solveIsLimitedSeparatelyAndRefusedBeforeAnythingIsReset() throws Exception {
        consent("s-user");
        String exam = createExam("s-user", "evaluating", "manual");
        mvc.perform(post("/examlense/api/tasks").with(as("s-user")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"exam_id\":\"" + exam + "\",\"position\":1,\"type\":\"text\",\"prompt\":\"Q\",\"points\":1}"))
            .andExpect(status().isOk());

        startSolve("s-user", exam).andExpect(status().isOk());
        startSolve("s-user", exam).andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("JOB_LIMIT_EXCEEDED"));

        // Parsing has its own count.
        startParse("s-user", createExam("s-user", "parsing", "pdf")).andExpect(status().isAccepted());
        mvc.perform(get("/examlense/api/me").with(as("s-user")))
            .andExpect(jsonPath("$.quota.solve_used").value(1))
            .andExpect(jsonPath("$.quota.parse_used").value(1));
    }
}
