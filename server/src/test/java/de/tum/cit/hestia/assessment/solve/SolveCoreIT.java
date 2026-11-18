package de.tum.cit.hestia.assessment.solve;

import de.tum.cit.hestia.assessment.AbstractIntegrationTest;
import de.tum.cit.hestia.assessment.examination.Examination;
import de.tum.cit.hestia.assessment.examination.ExaminationRepository;
import de.tum.cit.hestia.assessment.grading.Grade;
import de.tum.cit.hestia.assessment.grading.GradeRepository;
import de.tum.cit.hestia.assessment.shared.DefaultUser;
import de.tum.cit.hestia.assessment.taskblock.AIAnswer;
import de.tum.cit.hestia.assessment.taskblock.AIAnswerRepository;
import de.tum.cit.hestia.assessment.taskblock.TaskBlock;
import de.tum.cit.hestia.assessment.taskblock.TaskBlockRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class SolveCoreIT extends AbstractIntegrationTest {

    @Autowired SolveCore core;
    @Autowired ExaminationRepository exams;
    @Autowired TaskBlockRepository tasks;
    @Autowired AIAnswerRepository answers;
    @Autowired GradeRepository grades;

    @Test
    void retriggerReplacesTheCurrentAnswerAndInvalidatesItsGrade() {
        Examination exam = examination();
        TaskBlock task = task(exam, 0);
        AIAnswer first = answer(exam, task, "first");
        core.replaceAnswers(exam.getId(), List.of(first));

        Grade grade = new Grade();
        grade.setAnswer(answers.findByTaskId(task.getId()).orElseThrow());
        grade.setScore(BigDecimal.ONE);
        grades.saveAndFlush(grade);

        AIAnswer second = answer(exam, task, "second");
        core.replaceAnswers(exam.getId(), List.of(second));

        assertThat(answers.findByTaskId(task.getId())).get().extracting(AIAnswer::getId)
            .isEqualTo(second.getId());
        assertThat(answers.countByExamId(exam.getId())).isEqualTo(1);
        assertThat(grades.findById(grade.getId())).isEmpty();
    }

    @Test
    void overlappingReplacementsSerializeAndLeaveOneAnswer() {
        Examination exam = examination();
        TaskBlock task = task(exam, 0);

        CompletableFuture<Void> first = CompletableFuture.runAsync(() ->
            core.replaceAnswers(exam.getId(), List.of(answer(exam, task, "first"))));
        CompletableFuture<Void> second = CompletableFuture.runAsync(() ->
            core.replaceAnswers(exam.getId(), List.of(answer(exam, task, "second"))));
        CompletableFuture.allOf(first, second).join();

        assertThat(answers.findByTaskId(task.getId())).isPresent();
        assertThat(answers.countByExamId(exam.getId())).isEqualTo(1);
    }

    @Test
    void disjointSplitBatchesRemainParallelSafe() {
        Examination exam = examination();
        TaskBlock firstTask = task(exam, 0);
        TaskBlock secondTask = task(exam, 1);

        CompletableFuture<Void> first = CompletableFuture.runAsync(() ->
            core.replaceAnswers(exam.getId(), List.of(answer(exam, firstTask, "first"))));
        CompletableFuture<Void> second = CompletableFuture.runAsync(() ->
            core.replaceAnswers(exam.getId(), List.of(answer(exam, secondTask, "second"))));
        CompletableFuture.allOf(first, second).join();

        assertThat(answers.findByTaskId(firstTask.getId())).isPresent();
        assertThat(answers.findByTaskId(secondTask.getId())).isPresent();
        assertThat(answers.countByExamId(exam.getId())).isEqualTo(2);
    }

    private Examination examination() {
        Examination exam = new Examination();
        exam.setOwnerId(DefaultUser.ID);
        exam.setSource("manual");
        exam.setStatus("grading");
        return exams.save(exam);
    }

    private TaskBlock task(Examination exam, int position) {
        TaskBlock task = new TaskBlock();
        task.setExamId(exam.getId());
        task.setPosition(position);
        task.setType("text");
        return tasks.save(task);
    }

    private static AIAnswer answer(Examination exam, TaskBlock task, String text) {
        AIAnswer answer = new AIAnswer();
        answer.setTaskId(task.getId());
        answer.setExamId(exam.getId());
        answer.setAnswerText(text);
        answer.setProvider("openai");
        answer.setModel("gpt-5.5");
        return answer;
    }
}
