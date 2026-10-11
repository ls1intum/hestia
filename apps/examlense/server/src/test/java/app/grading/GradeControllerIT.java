package app.grading;

import app.AbstractIntegrationTest;
import app.examination.Examination;
import app.examination.ExaminationRepository;
import app.shared.DefaultUser;
import app.taskblock.AIAnswer;
import app.taskblock.AIAnswerRepository;
import app.taskblock.TaskBlock;
import app.taskblock.TaskBlockRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class GradeControllerIT extends AbstractIntegrationTest {

    @Autowired ExaminationRepository exams;
    @Autowired TaskBlockRepository tasks;
    @Autowired AIAnswerRepository answers;
    @Autowired MockMvc mvc;

    @Test
    void upsertTargetsCurrentAnswerAndKeepsTaskAndExamCompatibilityFields() throws Exception {
        Examination exam = examination();
        TaskBlock task = task(exam);
        AIAnswer answer = answer(exam, task);

        mvc.perform(put("/api/task-grades")
                .header("Authorization", "Bearer " + TEST_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request(task, exam)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.answer_id").value(answer.getId().toString()))
            .andExpect(jsonPath("$.task_id").value(task.getId().toString()))
            .andExpect(jsonPath("$.exam_id").value(exam.getId().toString()))
            .andExpect(jsonPath("$.score").value(1.5));
    }

    @Test
    void upsertRejectsTaskWithoutAnAnswer() throws Exception {
        Examination exam = examination();
        TaskBlock task = task(exam);

        mvc.perform(put("/api/task-grades")
                .header("Authorization", "Bearer " + TEST_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request(task, exam)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("The task has no AI answer to grade."));
    }

    private Examination examination() {
        Examination exam = new Examination();
        exam.setOwnerId(DefaultUser.ID);
        exam.setSource("manual");
        exam.setStatus("grading");
        return exams.save(exam);
    }

    private TaskBlock task(Examination exam) {
        TaskBlock task = new TaskBlock();
        task.setExamId(exam.getId());
        task.setPosition(0);
        task.setType("text");
        return tasks.save(task);
    }

    private AIAnswer answer(Examination exam, TaskBlock task) {
        AIAnswer answer = new AIAnswer();
        answer.setTaskId(task.getId());
        answer.setExamId(exam.getId());
        answer.setProvider("openai");
        answer.setModel("gpt-5.5");
        return answers.save(answer);
    }

    private static String request(TaskBlock task, Examination exam) {
        return """
            {"task_id":"%s","exam_id":"%s","score":1.5,"auto_graded":false}
            """.formatted(task.getId(), exam.getId());
    }
}
