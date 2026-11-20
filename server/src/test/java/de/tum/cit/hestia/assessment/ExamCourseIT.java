package de.tum.cit.hestia.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import de.tum.cit.hestia.core.AlignmentApi;
import de.tum.cit.hestia.core.AlignmentLinkInfo;
import de.tum.cit.hestia.core.DomainModule;
import de.tum.cit.hestia.core.GoalChange;
import de.tum.cit.hestia.core.GoalInfo;
import de.tum.cit.hestia.core.GoalOrigin;
import de.tum.cit.hestia.core.LearningGoalApi;
import de.tum.cit.hestia.core.NewGoal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** Exams live in core courses and their tasks reach goals through alignment links. */
@AutoConfigureMockMvc
class ExamCourseIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired LearningGoalApi goalApi;
    @Autowired AlignmentApi alignment;

    private String createExam(String user, String courseId) throws Exception {
        String body = "{\"title\":\"t\",\"source\":\"manual\""
            + (courseId == null ? "" : ",\"course_id\":\"" + courseId + "\"") + "}";
        String json = mvc.perform(post("/examlense/api/exams").with(as(user)).contentType(APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.id");
    }

    private String createTask(String user, String examId) throws Exception {
        String json = mvc.perform(post("/examlense/api/tasks").with(as(user)).contentType(APPLICATION_JSON)
                .content("{\"exam_id\":\"" + examId + "\",\"position\":1,\"type\":\"text\",\"prompt\":\"q\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.id");
    }

    private UUID course(String owner, String title) {
        return runAs(owner, () -> courseApi.create(title).id());
    }

    private GoalInfo goal(String user, UUID course, String text) {
        return runAs(user, () -> goalApi.create(course, NewGoal.of(text, GoalOrigin.MANUAL, DomainModule.ASSESSMENT)));
    }

    private void link(String user, GoalInfo goal, String taskId) {
        runAs(user, () -> alignment.link(goal.id(), DomainModule.ASSESSMENT, "TASK", taskId));
    }

    private List<UUID> linkedGoals(String user, String taskId) {
        return runAs(user, () -> alignment.goalsFor(DomainModule.ASSESSMENT, "TASK", taskId).stream()
            .map(AlignmentLinkInfo::goalId).toList());
    }

    private void member(UUID course, String owner, String user, String role) throws Exception {
        userId(user);
        mvc.perform(put("/core/api/courses/" + course + "/members").with(as(owner))
                .contentType(APPLICATION_JSON)
                .content("{\"externalId\":\"" + user + "\",\"role\":\"" + role + "\"}"))
            .andExpect(status().isOk());
    }

    @Test
    void anExamCreatedWithoutACourseLandsInTheUsersPersonalCatchAllCourse() throws Exception {
        String examId = createExam("alice", null);

        UUID personal = courseOf("alice");
        mvc.perform(get("/examlense/api/exams/" + examId).with(as("alice")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.course_id").value(personal.toString()));
        assertThat(runAs("alice", () -> courseApi.get(personal).personal())).isTrue();
    }

    @Test
    void movingAnExamRemovesTheLinksOfItsTasksToGoalsOfTheOldCourseOnly() throws Exception {
        UUID oldCourse = course("alice", "Old");
        UUID newCourse = course("alice", "New");
        UUID thirdCourse = course("alice", "Third");
        String examId = createExam("alice", oldCourse.toString());
        String taskId = createTask("alice", examId);
        GoalInfo oldGoal = goal("alice", oldCourse, "goal of the old course");
        GoalInfo newGoal = goal("alice", newCourse, "goal of the new course");
        GoalInfo otherGoal = goal("alice", thirdCourse, "goal of a third course");
        link("alice", oldGoal, taskId);
        link("alice", newGoal, taskId);
        link("alice", otherGoal, taskId);

        mvc.perform(put("/examlense/api/exams/" + examId + "/course").with(as("alice"))
                .contentType(APPLICATION_JSON).content("{\"courseId\":\"" + newCourse + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.course_id").value(newCourse.toString()));

        assertThat(linkedGoals("alice", taskId)).containsExactlyInAnyOrder(newGoal.id(), otherGoal.id());
    }

    @Test
    void movingAnExamNeedsEditorOnBothCourses() throws Exception {
        UUID source = course("alice", "Source");
        UUID readOnlyTarget = course("bob", "Read only target");
        UUID foreignTarget = course("bob", "Foreign target");
        UUID editableTarget = course("bob", "Editable target");
        member(readOnlyTarget, "bob", "alice", "VIEWER");
        member(editableTarget, "bob", "alice", "EDITOR");
        String examId = createExam("alice", source.toString());
        String taskId = createTask("alice", examId);
        GoalInfo sourceGoal = goal("alice", source, "goal of the source");
        link("alice", sourceGoal, taskId);

        // Target course: viewer -> 403, not a member -> 404; nothing changes.
        mvc.perform(put("/examlense/api/exams/" + examId + "/course").with(as("alice"))
                .contentType(APPLICATION_JSON).content("{\"courseId\":\"" + readOnlyTarget + "\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(put("/examlense/api/exams/" + examId + "/course").with(as("alice"))
                .contentType(APPLICATION_JSON).content("{\"courseId\":\"" + foreignTarget + "\"}"))
            .andExpect(status().isNotFound());
        assertThat(linkedGoals("alice", taskId)).containsExactly(sourceGoal.id());

        // Source course: a viewer there cannot move the exam out (403), a stranger sees no exam (404).
        member(source, "alice", "carol", "VIEWER");
        mvc.perform(put("/examlense/api/exams/" + examId + "/course").with(as("carol"))
                .contentType(APPLICATION_JSON).content("{\"courseId\":\"" + editableTarget + "\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(put("/examlense/api/exams/" + examId + "/course").with(as("dave"))
                .contentType(APPLICATION_JSON).content("{\"courseId\":\"" + editableTarget + "\"}"))
            .andExpect(status().isNotFound());

        mvc.perform(put("/examlense/api/exams/" + examId + "/course").with(as("alice"))
                .contentType(APPLICATION_JSON).content("{\"courseId\":\"" + editableTarget + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.course_id").value(editableTarget.toString()));
        assertThat(linkedGoals("alice", taskId)).isEmpty();
    }

    @Test
    void aGoalRenamedInCoreShowsItsNewTextInTheTaskListWithoutAnyImport() throws Exception {
        UUID course = course("alice", "Algorithms");
        String examId = createExam("alice", course.toString());
        String taskId = createTask("alice", examId);
        GoalInfo goal = goal("alice", course, "Explain recursion");
        link("alice", goal, taskId);

        mvc.perform(get("/examlense/api/exams/" + examId + "/tasks").with(as("alice")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].learning_goal_ids[0]").value(goal.id().toString()))
            .andExpect(jsonPath("$[0].learning_goals[0].text").value("Explain recursion"));

        runAs("alice", () -> goalApi.update(goal.id(), new GoalChange("Apply recursion to trees",
            goal.shortLabel(), goal.kind(), goal.role(), goal.bloomLevel(), goal.soloLevel())));

        mvc.perform(get("/examlense/api/exams/" + examId + "/tasks").with(as("alice")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].learning_goals[0].text").value("Apply recursion to trees"));
        mvc.perform(get("/examlense/api/exams/" + examId + "/learning-goals").with(as("alice")))
            .andExpect(jsonPath("$[0].text").value("Apply recursion to trees"));
    }

    @Test
    void deletingATaskOrAnExamRemovesItsGoalLinks() throws Exception {
        UUID course = course("alice", "Algorithms");
        String examId = createExam("alice", course.toString());
        String task1 = createTask("alice", examId);
        String task2 = createTask("alice", examId);
        GoalInfo goal = goal("alice", course, "g");
        link("alice", goal, task1);
        link("alice", goal, task2);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/examlense/api/tasks/" + task1).with(as("alice")))
            .andExpect(status().isNoContent());
        assertThat(linkedGoals("alice", task1)).isEmpty();
        assertThat(linkedGoals("alice", task2)).hasSize(1);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/examlense/api/exams/" + examId).with(as("alice")))
            .andExpect(status().isNoContent());
        assertThat(linkedGoals("alice", task2)).isEmpty();
    }
}
