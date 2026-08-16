package app.exam;

import app.AbstractIntegrationTest;
import app.shared.DefaultUser;
import app.task.Task;
import app.task.TaskAnswer;
import app.grading.TaskGrade;
import app.task.TaskOption;
import app.task.TaskAnswerRepository;
import app.grading.TaskGradeRepository;
import app.task.TaskRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the dashboard progress counts, especially the faithful "graded" rule
 * (persisted score OR auto-resolvable choice task) that mirrors the frontend
 * grading logic.
 */
@AutoConfigureMockMvc
class ExamProgressServiceIT extends AbstractIntegrationTest {

    @Autowired ExamProgressService progress;
    @Autowired ExamRepository exams;
    @Autowired TaskRepository tasks;
    @Autowired TaskAnswerRepository answers;
    @Autowired TaskGradeRepository grades;
    @Autowired MockMvc mvc;

    private Exam newExam() {
        Exam e = new Exam();
        e.setOwnerId(DefaultUser.ID);
        e.setSource("manual");
        e.setTitle("Progress");
        e.setStatus("grading");
        return exams.save(e);
    }

    private Task addTask(Exam e, int pos, String type, BigDecimal points, List<TaskOption> options) {
        Task t = new Task();
        t.setExamId(e.getId());
        t.setPosition(pos);
        t.setType(type);
        t.setPrompt("Q" + pos);
        t.setPoints(points);
        t.setOptions(options);
        return tasks.save(t);
    }

    private void addAnswer(Exam e, Task t) {
        TaskAnswer a = new TaskAnswer();
        a.setTaskId(t.getId());
        a.setExamId(e.getId());
        a.setProvider("openai");
        a.setModel("gpt-5.5");
        answers.save(a);
    }

    private void addGrade(Exam e, Task t, BigDecimal score) {
        TaskGrade g = new TaskGrade();
        g.setTaskId(t.getId());
        g.setExamId(e.getId());
        g.setScore(score);
        g.setAutoGraded(false);
        grades.save(g);
    }

    @Test
    void countsReflectScoredAnsweredAndFaithfulGradedRule() {
        Exam e = newExam();
        List<TaskOption> withCorrect = List.of(
            new TaskOption(UUID.randomUUID().toString(), "a", true),
            new TaskOption(UUID.randomUUID().toString(), "b", false));
        List<TaskOption> noCorrect = List.of(
            new TaskOption(UUID.randomUUID().toString(), "a", false));

        // 1: text task with a persisted manual grade -> scored + graded (not answered)
        Task t1 = addTask(e, 0, "text", new BigDecimal("2"), null);
        addGrade(e, t1, new BigDecimal("1.5"));
        // 2: auto-resolvable MC (points, correct option, answered) -> scored + answered + graded
        Task t2 = addTask(e, 1, "single_choice", new BigDecimal("1"), withCorrect);
        addAnswer(e, t2);
        // 3: MC answered but points unset -> answered only (not scored, not graded)
        Task t3 = addTask(e, 2, "single_choice", null, withCorrect);
        addAnswer(e, t3);
        // 4: MC with points but no correct option and no grade -> scored only (not graded)
        Task t4 = addTask(e, 3, "single_choice", new BigDecimal("1"), noCorrect);
        addAnswer(e, t4);
        // 5: text task, no grade -> scored only (not graded, choice-only auto-rule excludes text)
        addTask(e, 4, "text", new BigDecimal("3"), null);

        var counts = progress.countsFor(List.of(e.getId())).get(e.getId());

        assertThat(counts.taskCount()).isEqualTo(5);
        assertThat(counts.scoredCount()).isEqualTo(4);   // t1,t2,t4,t5
        assertThat(counts.answeredCount()).isEqualTo(3); // t2,t3,t4
        assertThat(counts.gradedCount()).isEqualTo(2);   // t1 (manual), t2 (auto)
    }

    @Test
    void listEndpointFlattensCountsAlongsideExamFields() throws Exception {
        Exam e = newExam();
        e.setTitle("list-shape-" + UUID.randomUUID());
        exams.save(e);
        addTask(e, 0, "text", new BigDecimal("2"), null); // 1 task, scored, ungraded

        // The frontend reads ExamListItem = Exam fields + counts at the top level,
        // so the counts must sit next to `id`/`status`, not nested.
        mvc.perform(get("/api/exams").header("Authorization", "Bearer " + TEST_TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == '" + e.getId() + "')].status").value("grading"))
            .andExpect(jsonPath("$[?(@.id == '" + e.getId() + "')].task_count").value(1))
            .andExpect(jsonPath("$[?(@.id == '" + e.getId() + "')].scored_count").value(1))
            .andExpect(jsonPath("$[?(@.id == '" + e.getId() + "')].graded_count").value(0));
    }

    @Test
    void emptyExamAndUnknownIdsMapToZero() {
        Exam empty = newExam();
        var counts = progress.countsFor(List.of(empty.getId()));
        assertThat(counts.get(empty.getId())).isEqualTo(ExamProgressService.Counts.EMPTY);
        assertThat(progress.countsFor(List.of())).isEmpty();
    }
}
