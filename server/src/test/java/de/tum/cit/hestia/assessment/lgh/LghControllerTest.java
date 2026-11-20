package de.tum.cit.hestia.assessment.lgh;

import de.tum.cit.hestia.assessment.AbstractIntegrationTest;
import de.tum.cit.hestia.assessment.examination.Examination;
import de.tum.cit.hestia.assessment.examination.ExaminationRepository;
import de.tum.cit.hestia.assessment.taskblock.TaskBlock;
import de.tum.cit.hestia.assessment.taskblock.TaskBlockRepository;
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

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The course picker and the goals of an exam are served from the core course and goal APIs. */
@AutoConfigureMockMvc
class LghControllerTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ExaminationRepository exams;
    @Autowired TaskBlockRepository tasks;
    @Autowired LearningGoalApi goalApi;
    @Autowired AlignmentApi alignment;

    @Test
    void listsTheCallersCoursesWithStringIdsAndTheFlaggedPersonalCourse() throws Exception {
        UUID personal = courseOf("alice");
        UUID algorithms = runAs("alice", () -> courseApi.create("Algorithms").id());

        mvc.perform(get("/examlense/api/lgh/courses").with(as("alice")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[?(@.id == '" + algorithms + "')].name").value("Algorithms"))
            .andExpect(jsonPath("$[?(@.id == '" + algorithms + "')].personal").value(false))
            .andExpect(jsonPath("$[?(@.id == '" + personal + "')].personal").value(true));
    }

    @Test
    void anotherUserDoesNotSeeTheCoursesOfAlice() throws Exception {
        runAs("alice", () -> courseApi.create("Algorithms"));

        mvc.perform(get("/examlense/api/lgh/courses").with(as("bob")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.name == 'Algorithms')]").isEmpty());
    }

    @Test
    void createsACourseOwnedByTheCaller() throws Exception {
        mvc.perform(post("/examlense/api/lgh/courses").with(as("alice"))
                .contentType(APPLICATION_JSON).content("{\"name\":\" Algorithms \"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Algorithms"))
            .andExpect(jsonPath("$.role").value("OWNER"))
            .andExpect(jsonPath("$.id").isString());
    }

    @Test
    void blankCourseNameIsRejected() throws Exception {
        mvc.perform(post("/examlense/api/lgh/courses").with(as("alice"))
                .contentType(APPLICATION_JSON).content("{\"name\":\"  \"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void resolvedGoalsOfAnExamAreThoseLinkedToItsTasks() throws Exception {
        UUID course = courseOf("alice");
        Examination exam = exam(course);
        TaskBlock task = task(exam);
        GoalInfo kept = runAs("alice",
            () -> goalApi.create(course, NewGoal.of("kept", GoalOrigin.EXAM, DomainModule.ASSESSMENT)));
        runAs("alice", () -> goalApi.create(course, NewGoal.of("not linked", GoalOrigin.EXAM, DomainModule.ASSESSMENT)));
        runAs("alice", () -> alignment.link(kept.id(), DomainModule.ASSESSMENT, "TASK", task.getId().toString()));

        mvc.perform(get("/examlense/api/exams/" + exam.getId() + "/learning-goals").with(as("alice")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(kept.id().toString()))
            .andExpect(jsonPath("$[0].text").value("kept"));
    }

    @Test
    void goalsOfAnExamOfAnotherUsersCourseAreNotFound() throws Exception {
        Examination exam = exam(courseOf("alice"));

        mvc.perform(get("/examlense/api/exams/" + exam.getId() + "/learning-goals").with(as("bob")))
            .andExpect(status().isNotFound());
    }

    private Examination exam(UUID course) {
        Examination exam = new Examination();
        exam.setCreatedBy(userId("alice"));
        exam.setCourseId(course);
        exam.setSource("manual");
        exam.setStatus("draft");
        return exams.save(exam);
    }

    private TaskBlock task(Examination exam) {
        TaskBlock task = new TaskBlock();
        task.setExamId(exam.getId());
        task.setPosition(0);
        task.setType("text");
        return tasks.save(task);
    }
}
