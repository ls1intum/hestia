package de.tum.cit.hestia.assessment.lgh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.tum.cit.hestia.assessment.AbstractIntegrationTest;
import de.tum.cit.hestia.core.AlignmentApi;
import de.tum.cit.hestia.core.DomainModule;
import de.tum.cit.hestia.core.GoalInfo;
import de.tum.cit.hestia.core.GoalOrigin;
import de.tum.cit.hestia.core.LearningGoalApi;
import de.tum.cit.hestia.core.NewGoal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** A changed or removed goal marks the linked exam task for review until the teacher confirms it. */
@AutoConfigureMockMvc
class TaskGoalReviewIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired LearningGoalApi goalApi;
    @Autowired AlignmentApi alignment;
    private final ObjectMapper json = new ObjectMapper();

    private JsonNode body(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return json.readTree(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode taskGoals(String examId) throws Exception {
        return body(mvc.perform(get("/examlense/api/exams/" + examId + "/tasks").with(as("alice")))).get(0)
            .get("learning_goals");
    }

    @Test
    void aChangedGoalMarksTheTaskGoalUntilItIsConfirmed() throws Exception {
        UUID course = runAs("alice", () -> courseApi.create("Review course").id());
        String examId = body(mvc.perform(post("/examlense/api/exams").with(as("alice")).contentType(APPLICATION_JSON)
            .content("{\"title\":\"Midterm\",\"source\":\"manual\",\"course_id\":\"" + course + "\"}"))).get("id").asText();
        String taskId = body(mvc.perform(post("/examlense/api/tasks").with(as("alice")).contentType(APPLICATION_JSON)
            .content("{\"exam_id\":\"" + examId + "\",\"position\":1,\"type\":\"text\",\"prompt\":\"Explain recursion\"}")))
            .get("id").asText();
        GoalInfo goal = runAs("alice", () -> goalApi.create(course,
            NewGoal.of("Understand recursion", GoalOrigin.MANUAL, DomainModule.ASSESSMENT)));
        runAs("alice", () -> alignment.link(goal.id(), DomainModule.ASSESSMENT, "TASK", taskId));

        assertThat(taskGoals(examId).get(0).get("review").isNull()).isTrue();

        runAs("alice", () -> goalApi.update(goal.id(), goal.asChange().withText("Apply recursion")));

        JsonNode marked = taskGoals(examId).get(0);
        assertThat(marked.get("review").asText()).isEqualTo("GOAL_CHANGED");
        assertThat(marked.get("course_id").asText()).isEqualTo(course.toString());
        assertThat(marked.get("removed").asBoolean()).isFalse();
        String linkId = marked.get("review_link_id").asText();

        JsonNode reviews = body(mvc.perform(get("/core/api/courses/" + course + "/reviews").with(as("alice"))));
        assertThat(reviews).hasSize(1);
        assertThat(reviews.get(0).get("targetLabel").asText()).isEqualTo("Midterm – Explain recursion");

        mvc.perform(post("/core/api/courses/" + course + "/reviews/" + linkId + "/confirm").with(as("alice")))
            .andExpect(status().isOk());
        assertThat(taskGoals(examId).get(0).get("review").isNull()).isTrue();

        runAs("alice", () -> goalApi.remove(goal.id()));
        JsonNode removed = taskGoals(examId).get(0);
        assertThat(removed.get("review").asText()).isEqualTo("GOAL_REMOVED");
        assertThat(removed.get("removed").asBoolean()).isTrue();
    }
}
