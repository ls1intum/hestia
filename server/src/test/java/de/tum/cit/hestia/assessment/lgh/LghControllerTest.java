package de.tum.cit.hestia.assessment.lgh;

import de.tum.cit.hestia.assessment.AbstractIntegrationTest;
import de.tum.cit.hestia.assessment.examination.Examination;
import de.tum.cit.hestia.assessment.examination.ExaminationRepository;
import de.tum.cit.hestia.assessment.shared.DefaultUser;
import de.tum.cit.hestia.assessment.taskblock.TaskBlock;
import de.tum.cit.hestia.assessment.taskblock.TaskBlockRepository;
import de.tum.cit.hestia.core.spi.ExamGoalDerivation;
import de.tum.cit.hestia.core.spi.GoalDirectory;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The course picker talks to the materials module through the {@link GoalDirectory} port. Any
 * failure of the port, including an unknown course, reaches the client as 502 so its UI degrades.
 */
@AutoConfigureMockMvc
class LghControllerTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ExaminationRepository exams;
    @Autowired TaskBlockRepository tasks;

    @MockitoBean GoalDirectory goalDirectory;
    @MockitoBean ExamGoalDerivation derivation;

    private static String bearer() {
        return "Bearer " + TEST_TOKEN;
    }

    @Test
    void listsCoursesAcrossAllPages() throws Exception {
        when(goalDirectory.listCourses(0, 100)).thenReturn(new GoalDirectory.Page<>(
            List.of(new GoalDirectory.Course(1, "A")), 0, 100, 2, 2));
        when(goalDirectory.listCourses(1, 100)).thenReturn(new GoalDirectory.Page<>(
            List.of(new GoalDirectory.Course(2, "B")), 1, 100, 2, 2));

        mvc.perform(get("/examlense/api/lgh/courses").header("Authorization", bearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].name").value("A"))
            .andExpect(jsonPath("$[1].id").value(2));
    }

    @Test
    void listingFailureBecomesBadGateway() throws Exception {
        when(goalDirectory.listCourses(anyInt(), anyInt())).thenThrow(new IllegalStateException("down"));

        mvc.perform(get("/examlense/api/lgh/courses").header("Authorization", bearer()))
            .andExpect(status().isBadGateway());
    }

    @Test
    void createsACourse() throws Exception {
        when(goalDirectory.createCourse("Algorithms")).thenReturn(new GoalDirectory.Course(7, "Algorithms"));

        mvc.perform(post("/examlense/api/lgh/courses").header("Authorization", bearer())
                .contentType(APPLICATION_JSON).content("{\"name\":\" Algorithms \"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(7));
    }

    @Test
    void blankCourseNameIsRejected() throws Exception {
        mvc.perform(post("/examlense/api/lgh/courses").header("Authorization", bearer())
                .contentType(APPLICATION_JSON).content("{\"name\":\"  \"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void createFailureBecomesBadGateway() throws Exception {
        when(goalDirectory.createCourse(anyString())).thenThrow(new RuntimeException("down"));

        mvc.perform(post("/examlense/api/lgh/courses").header("Authorization", bearer())
                .contentType(APPLICATION_JSON).content("{\"name\":\"X\"}"))
            .andExpect(status().isBadGateway());
    }

    @Test
    void resolvedGoalsOfAnExamAreNarrowedToTheIdsOnItsTasks() throws Exception {
        Examination exam = examinationLinkedTo(5L);
        taskWithGoals(exam, 11L);
        when(goalDirectory.goals(5L)).thenReturn(List.of(goal(11L, "kept"), goal(12L, "other")));

        mvc.perform(get("/examlense/api/exams/" + exam.getId() + "/learning-goals")
                .header("Authorization", bearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].text").value("kept"));
    }

    @Test
    void unknownCourseOfAnExamBecomesBadGateway() throws Exception {
        Examination exam = examinationLinkedTo(404L);
        taskWithGoals(exam, 11L);
        when(goalDirectory.goals(anyLong())).thenThrow(new NoSuchElementException("no course"));

        mvc.perform(get("/examlense/api/exams/" + exam.getId() + "/learning-goals")
                .header("Authorization", bearer()))
            .andExpect(status().isBadGateway());
    }

    private Examination examinationLinkedTo(long courseId) {
        Examination exam = new Examination();
        exam.setOwnerId(DefaultUser.ID);
        exam.setSource("manual");
        exam.setStatus("draft");
        exam.setLghCourseId(courseId);
        return exams.save(exam);
    }

    private void taskWithGoals(Examination exam, long goalId) {
        TaskBlock task = new TaskBlock();
        task.setExamId(exam.getId());
        task.setPosition(0);
        task.setType("text");
        task.setLearningGoalIds(List.of(goalId));
        tasks.save(task);
    }

    private static GoalDirectory.Goal goal(long id, String text) {
        return new GoalDirectory.Goal(id, text, "LEARNING", "PRIMARY", "EXAM", "PENDING",
            "APPLY", "MULTISTRUCTURAL", null, new GoalDirectory.Hierarchy(null, null, null), List.of());
    }
}
