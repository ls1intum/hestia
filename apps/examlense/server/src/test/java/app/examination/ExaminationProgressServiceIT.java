package app.examination;

import app.AbstractIntegrationTest;
import app.shared.DefaultUser;
import app.taskblock.TaskBlock;
import app.taskblock.AIAnswer;
import app.grading.Grade;
import app.taskblock.AnswerOption;
import app.taskblock.AIAnswerRepository;
import app.grading.GradeRepository;
import app.taskblock.TaskBlockRepository;
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
class ExaminationProgressServiceIT extends AbstractIntegrationTest {

    @Autowired ExaminationProgressService progress;
    @Autowired ExaminationRepository exams;
    @Autowired TaskBlockRepository tasks;
    @Autowired AIAnswerRepository answers;
    @Autowired GradeRepository grades;
    @Autowired MockMvc mvc;

    private Examination newExamination() {
        Examination e = new Examination();
        e.setOwnerId(DefaultUser.ID);
        e.setSource("manual");
        e.setTitle("Progress");
        e.setStatus("grading");
        return exams.save(e);
    }

    private TaskBlock addTaskBlock(Examination e, int pos, String type, BigDecimal points, List<AnswerOption> options) {
        TaskBlock t = new TaskBlock();
        t.setExamId(e.getId());
        t.setPosition(pos);
        t.setType(type);
        t.setPrompt("Q" + pos);
        t.setPoints(points);
        t.setOptions(options);
        return tasks.save(t);
    }

    private void addAnswer(Examination e, TaskBlock t) {
        AIAnswer a = new AIAnswer();
        a.setTaskId(t.getId());
        a.setExamId(e.getId());
        a.setProvider("openai");
        a.setModel("gpt-5.5");
        answers.save(a);
    }

    private void addGrade(Examination e, TaskBlock t, BigDecimal score) {
        Grade g = new Grade();
        g.setTaskId(t.getId());
        g.setExamId(e.getId());
        g.setScore(score);
        g.setAutoGraded(false);
        grades.save(g);
    }

    @Test
    void countsReflectScoredAnsweredAndFaithfulGradedRule() {
        Examination e = newExamination();
        List<AnswerOption> withCorrect = List.of(
            new AnswerOption(UUID.randomUUID().toString(), "a", true),
            new AnswerOption(UUID.randomUUID().toString(), "b", false));
        List<AnswerOption> noCorrect = List.of(
            new AnswerOption(UUID.randomUUID().toString(), "a", false));

        // 1: text task with a persisted manual grade -> scored + graded (not answered)
        TaskBlock t1 = addTaskBlock(e, 0, "text", new BigDecimal("2"), null);
        addGrade(e, t1, new BigDecimal("1.5"));
        // 2: auto-resolvable MC (points, correct option, answered) -> scored + answered + graded
        TaskBlock t2 = addTaskBlock(e, 1, "single_choice", new BigDecimal("1"), withCorrect);
        addAnswer(e, t2);
        // 3: MC answered but points unset -> answered only (not scored, not graded)
        TaskBlock t3 = addTaskBlock(e, 2, "single_choice", null, withCorrect);
        addAnswer(e, t3);
        // 4: MC with points but no correct option and no grade -> scored only (not graded)
        TaskBlock t4 = addTaskBlock(e, 3, "single_choice", new BigDecimal("1"), noCorrect);
        addAnswer(e, t4);
        // 5: text task, no grade -> scored only (not graded, choice-only auto-rule excludes text)
        addTaskBlock(e, 4, "text", new BigDecimal("3"), null);

        var counts = progress.countsFor(List.of(e.getId())).get(e.getId());

        assertThat(counts.taskCount()).isEqualTo(5);
        assertThat(counts.scoredCount()).isEqualTo(4);   // t1,t2,t4,t5
        assertThat(counts.answeredCount()).isEqualTo(3); // t2,t3,t4
        assertThat(counts.gradedCount()).isEqualTo(2);   // t1 (manual), t2 (auto)
    }

    @Test
    void listEndpointFlattensCountsAlongsideExaminationFields() throws Exception {
        Examination e = newExamination();
        e.setTitle("list-shape-" + UUID.randomUUID());
        exams.save(e);
        addTaskBlock(e, 0, "text", new BigDecimal("2"), null); // 1 task, scored, ungraded

        // The frontend reads ExaminationListItem = Examination fields + counts at the top level,
        // so the counts must sit next to `id`/`status`, not nested.
        mvc.perform(get("/api/exams").header("Authorization", "Bearer " + TEST_TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == '" + e.getId() + "')].status").value("grading"))
            .andExpect(jsonPath("$[?(@.id == '" + e.getId() + "')].task_count").value(1))
            .andExpect(jsonPath("$[?(@.id == '" + e.getId() + "')].scored_count").value(1))
            .andExpect(jsonPath("$[?(@.id == '" + e.getId() + "')].graded_count").value(0));
    }

    @Test
    void emptyExaminationAndUnknownIdsMapToZero() {
        Examination empty = newExamination();
        var counts = progress.countsFor(List.of(empty.getId()));
        assertThat(counts.get(empty.getId())).isEqualTo(ExaminationProgressService.Counts.EMPTY);
        assertThat(progress.countsFor(List.of())).isEmpty();
    }
}
