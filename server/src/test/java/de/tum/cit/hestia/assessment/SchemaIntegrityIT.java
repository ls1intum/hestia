package de.tum.cit.hestia.assessment;

import de.tum.cit.hestia.assessment.examination.Examination;
import de.tum.cit.hestia.assessment.grading.Grade;
import de.tum.cit.hestia.assessment.grading.GradeRepository;
import de.tum.cit.hestia.assessment.section.Section;
import de.tum.cit.hestia.assessment.section.SectionBlock;
import de.tum.cit.hestia.assessment.taskblock.TaskBlock;
import de.tum.cit.hestia.assessment.taskblock.AIAnswer;
import de.tum.cit.hestia.assessment.examination.ExaminationRepository;
import de.tum.cit.hestia.assessment.section.SectionBlockRepository;
import de.tum.cit.hestia.assessment.section.SectionRepository;
import de.tum.cit.hestia.assessment.taskblock.AIAnswerRepository;
import de.tum.cit.hestia.assessment.taskblock.TaskBlockRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The database is the last line of defence for data correctness. These tests run
 * against a real Postgres (migrations applied) and pin the invariants the app
 * relies on: FK cascades clean up children, CHECK constraints reject invalid
 * enum values, and the evaluation-finalize trigger advances exam status.
 */
class SchemaIntegrityIT extends AbstractIntegrationTest {

    @Autowired ExaminationRepository exams;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired SectionRepository sections;
    @Autowired TaskBlockRepository tasks;
    @Autowired SectionBlockRepository blocks;
    @Autowired AIAnswerRepository answers;
    @Autowired GradeRepository grades;

    private UUID owner() {
        return userId("schema");
    }

    private Examination newExamination(String status) {
        Examination e = new Examination();
        e.setCreatedBy(owner());
        e.setSource("manual");
        e.setStatus(status);
        return exams.save(e);
    }

    private Section newSection(UUID examId) {
        Section s = new Section();
        s.setExamId(examId);
        s.setPosition(0);
        return sections.save(s);
    }

    private TaskBlock newTaskBlock(UUID examId, UUID sectionId) {
        TaskBlock t = new TaskBlock();
        t.setExamId(examId);
        t.setSectionId(sectionId);
        t.setPosition(0);
        t.setType("text");
        return tasks.save(t);
    }

    @Test
    void deletingAnExaminationCascadesToAllItsChildrenIncludingAnswers() {
        Examination exam = newExamination("draft");
        Section section = newSection(exam.getId());
        TaskBlock task = newTaskBlock(exam.getId(), section.getId());
        SectionBlock block = new SectionBlock();
        block.setSectionId(section.getId());
        block.setExamId(exam.getId());
        block.setPosition(0);
        block.setContent("ctx");
        blocks.save(block);
        AIAnswer answer = new AIAnswer();
        answer.setTaskId(task.getId());
        answer.setExamId(exam.getId());
        answer.setProvider("openai");
        answer.setModel("gpt-5.5");
        answers.save(answer);

        exams.delete(exam);

        assertThat(sections.findByExamIdOrderByPositionAsc(exam.getId())).isEmpty();
        assertThat(tasks.findByExamIdOrderByPositionAsc(exam.getId())).isEmpty();
        assertThat(blocks.findByExamIdOrderByPositionAsc(exam.getId())).isEmpty();
        // V5 added FKs on task_answers → answers now cascade too (previously orphaned).
        assertThat(answers.findByExamId(exam.getId())).isEmpty();
    }

    @Test
    void deletingATaskBlockCascadesToItsAnswers() {
        Examination exam = newExamination("draft");
        TaskBlock task = newTaskBlock(exam.getId(), null);
        AIAnswer answer = new AIAnswer();
        answer.setTaskId(task.getId());
        answer.setExamId(exam.getId());
        answer.setProvider("openai");
        answer.setModel("gpt-5.5");
        answers.save(answer);

        tasks.delete(task);

        assertThat(answers.findByTaskId(task.getId())).isEmpty();
    }

    @Test
    void aTaskBlockHasAtMostOneCurrentAnswer() {
        Examination exam = newExamination("draft");
        TaskBlock task = newTaskBlock(exam.getId(), null);
        answers.saveAndFlush(answerFor(exam, task));

        assertThatThrownBy(() -> answers.saveAndFlush(answerFor(exam, task)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingAnAnswerCascadesToItsGrade() {
        Examination exam = newExamination("grading");
        TaskBlock task = newTaskBlock(exam.getId(), null);
        AIAnswer answer = answers.saveAndFlush(answerFor(exam, task));
        Grade grade = new Grade();
        grade.setAnswer(answer);
        grade.setScore(java.math.BigDecimal.ONE);
        grades.saveAndFlush(grade);

        answers.delete(answer);
        answers.flush();

        assertThat(grades.findById(grade.getId())).isEmpty();
    }

    @Test
    void aGradeRequiresAnAnswer() {
        Grade grade = new Grade();
        grade.setScore(java.math.BigDecimal.ONE);

        assertThatThrownBy(() -> grades.saveAndFlush(grade))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static AIAnswer answerFor(Examination exam, TaskBlock task) {
        AIAnswer answer = new AIAnswer();
        answer.setTaskId(task.getId());
        answer.setExamId(exam.getId());
        answer.setProvider("openai");
        answer.setModel("gpt-5.5");
        return answer;
    }

    @Test
    void invalidStatusIsRejectedByTheCheckConstraint() {
        Examination bad = new Examination();
        bad.setCreatedBy(owner()); // a real user, so the failure can only be the status CHECK
        bad.setSource("manual");
        bad.setStatus("not-a-real-status");

        assertThatThrownBy(() -> exams.saveAndFlush(bad))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void everyAssessmentTableIsIsolatedByOrganisation() {
        var tables = jdbc.queryForList(
            "select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace "
                + "where n.nspname = 'assessment' and c.relkind = 'r' and not c.relrowsecurity", String.class);
        assertThat(tables).as("assessment tables without row-level security").isEmpty();
        var withoutOrg = jdbc.queryForList(
            "select table_name from information_schema.tables t where table_schema = 'assessment' "
                + "and table_type = 'BASE TABLE' and not exists (select 1 from information_schema.columns c "
                + "where c.table_schema = t.table_schema and c.table_name = t.table_name and c.column_name = 'org_id')",
            String.class);
        assertThat(withoutOrg).as("assessment tables without org_id").isEmpty();
    }

    @Test
    void examCreatorMustReferenceARealUser() {
        Examination orphan = new Examination();
        orphan.setCreatedBy(UUID.randomUUID());
        orphan.setSource("manual");
        orphan.setStatus("draft");

        assertThatThrownBy(() -> exams.saveAndFlush(orphan))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void invalidTaskBlockTypeIsRejectedByTheCheckConstraint() {
        Examination exam = newExamination("draft");
        TaskBlock bad = new TaskBlock();
        bad.setExamId(exam.getId());
        bad.setPosition(0);
        bad.setType("essay"); // not in (single_choice, multiple_choice, text)

        assertThatThrownBy(() -> tasks.saveAndFlush(bad))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

}
