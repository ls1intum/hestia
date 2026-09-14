package app;

import app.exam.Exam;
import app.section.Section;
import app.section.SectionBlock;
import app.task.Task;
import app.task.TaskAnswer;
import app.exam.ExamRepository;
import app.section.SectionBlockRepository;
import app.section.SectionRepository;
import app.task.TaskAnswerRepository;
import app.task.TaskRepository;
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

    @Autowired ExamRepository exams;
    @Autowired app.user.UserRepository users;
    @Autowired SectionRepository sections;
    @Autowired TaskRepository tasks;
    @Autowired SectionBlockRepository blocks;
    @Autowired TaskAnswerRepository answers;

    /** A real user, because {@code exams.owner_id} is now a foreign key. */
    private UUID owner() {
        if (owner == null) owner = createUser("schema-" + UUID.randomUUID()).id();
        return owner;
    }

    private UUID owner;

    private Exam newExam(String status) {
        Exam e = new Exam();
        e.setOwnerId(owner());
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

    private Task newTask(UUID examId, UUID sectionId) {
        Task t = new Task();
        t.setExamId(examId);
        t.setSectionId(sectionId);
        t.setPosition(0);
        t.setType("text");
        return tasks.save(t);
    }

    @Test
    void deletingAnExamCascadesToAllItsChildrenIncludingAnswers() {
        Exam exam = newExam("draft");
        Section section = newSection(exam.getId());
        Task task = newTask(exam.getId(), section.getId());
        SectionBlock block = new SectionBlock();
        block.setSectionId(section.getId());
        block.setExamId(exam.getId());
        block.setPosition(0);
        block.setContent("ctx");
        blocks.save(block);
        TaskAnswer answer = new TaskAnswer();
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
    void deletingATaskCascadesToItsAnswers() {
        Exam exam = newExam("draft");
        Task task = newTask(exam.getId(), null);
        TaskAnswer answer = new TaskAnswer();
        answer.setTaskId(task.getId());
        answer.setExamId(exam.getId());
        answer.setProvider("openai");
        answer.setModel("gpt-5.5");
        answers.save(answer);

        tasks.delete(task);

        assertThat(answers.findByTaskId(task.getId())).isEmpty();
    }

    @Test
    void invalidStatusIsRejectedByTheCheckConstraint() {
        Exam bad = new Exam();
        bad.setOwnerId(owner()); // a real owner, so the failure can only be the status CHECK
        bad.setSource("manual");
        bad.setStatus("not-a-real-status");

        assertThatThrownBy(() -> exams.saveAndFlush(bad))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * The shared bootstrap token resolves to this row and its value is committed to
     * a public repository, so an admin flag here would be admin for anyone who
     * reads it.
     */
    @Test
    void theSeededLegacyUserIsNotAnAdmin() {
        assertThat(users.findById(app.shared.DefaultUser.ID).orElseThrow().isAdmin()).isFalse();
    }

    @Test
    void examOwnerMustReferenceARealUser() {
        Exam orphan = new Exam();
        orphan.setOwnerId(UUID.randomUUID());
        orphan.setSource("manual");
        orphan.setStatus("draft");

        assertThatThrownBy(() -> exams.saveAndFlush(orphan))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void invalidTaskTypeIsRejectedByTheCheckConstraint() {
        Exam exam = newExam("draft");
        Task bad = new Task();
        bad.setExamId(exam.getId());
        bad.setPosition(0);
        bad.setType("essay"); // not in (single_choice, multiple_choice, text)

        assertThatThrownBy(() -> tasks.saveAndFlush(bad))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

}
