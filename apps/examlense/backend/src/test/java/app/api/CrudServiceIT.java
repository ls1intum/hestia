package app.api;

import app.AbstractIntegrationTest;
import app.persistence.DefaultUser;
import app.persistence.entity.Exam;
import app.persistence.entity.Section;
import app.persistence.entity.SectionBlock;
import app.persistence.entity.Task;
import app.persistence.entity.TaskAnswer;
import app.persistence.repository.ExamRepository;
import app.persistence.repository.SectionBlockRepository;
import app.persistence.repository.SectionRepository;
import app.persistence.repository.TaskAnswerRepository;
import app.persistence.repository.TaskRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CrudService owns the multi-step operations that must stay atomic and correct:
 * deep exam duplication and section unconfirm (which also clears AI answers and
 * detaches learning goals). Run against a real DB since they touch several tables.
 */
class CrudServiceIT extends AbstractIntegrationTest {

    @Autowired CrudService crud;
    @Autowired ExamRepository exams;
    @Autowired SectionRepository sections;
    @Autowired TaskRepository tasks;
    @Autowired SectionBlockRepository blocks;
    @Autowired TaskAnswerRepository answers;

    private Exam seedExamWithContent() {
        Exam exam = new Exam();
        exam.setOwnerId(DefaultUser.ID);
        exam.setSource("manual");
        exam.setTitle("Original");
        exam.setStatus("ready");
        exams.save(exam);

        Section section = new Section();
        section.setExamId(exam.getId());
        section.setName("Part A");
        section.setPosition(0);
        sections.save(section);

        SectionBlock block = new SectionBlock();
        block.setExamId(exam.getId());
        block.setSectionId(section.getId());
        block.setContent("intro context");
        block.setPosition(0);
        blocks.save(block);

        Task task = new Task();
        task.setExamId(exam.getId());
        task.setSectionId(section.getId());
        task.setType("text");
        task.setPrompt("Explain X");
        task.setPosition(1);
        task.setLearningGoalIds(List.of(11L, 22L));
        tasks.save(task);

        return exam;
    }

    @Test
    void duplicateExamDeepCopiesContentAsAFreshDraft() {
        Exam src = seedExamWithContent();

        Exam copy = crud.duplicateExam(src, DefaultUser.ID.toString());

        assertThat(copy.getId()).isNotEqualTo(src.getId());
        assertThat(copy.getTitle()).isEqualTo("Original (Copy)");
        assertThat(copy.getStatus()).isEqualTo("draft");
        assertThat(copy.getOwnerId()).isEqualTo(DefaultUser.ID);

        assertThat(sections.findByExamIdOrderByPositionAsc(copy.getId())).hasSize(1);
        assertThat(blocks.findByExamIdOrderByPositionAsc(copy.getId())).hasSize(1);
        List<Task> copiedTasks = tasks.findByExamIdOrderByPositionAsc(copy.getId());
        assertThat(copiedTasks).hasSize(1);
        // Goal ids belong to the source's LGH goals — must NOT be copied.
        assertThat(copiedTasks.get(0).getLearningGoalIds()).isNull();
    }

    @Test
    void unconfirmSectionClearsConfirmationDropsAnswersAndDetachesGoals() {
        Exam exam = seedExamWithContent();
        Section section = sections.findByExamIdOrderByPositionAsc(exam.getId()).get(0);
        section.setConfirmedAt(OffsetDateTime.now());
        sections.save(section);

        Task task = tasks.findByExamIdOrderByPositionAsc(exam.getId()).get(0);
        TaskAnswer answer = new TaskAnswer();
        answer.setTaskId(task.getId());
        answer.setExamId(exam.getId());
        answer.setProvider("openai");
        answer.setModel("gpt-5.5");
        answers.save(answer);

        crud.unconfirmSection(section);

        Section reloaded = sections.findById(section.getId()).orElseThrow();
        assertThat(reloaded.getConfirmedAt()).isNull();
        assertThat(answers.findByTaskId(task.getId())).isEmpty();
        assertThat(tasks.findById(task.getId()).orElseThrow().getLearningGoalIds()).isNull();
    }
}
