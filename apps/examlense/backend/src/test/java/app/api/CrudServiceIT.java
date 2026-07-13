package app.api;

import app.AbstractIntegrationTest;
import app.persistence.DefaultUser;
import app.persistence.entity.Exam;
import app.persistence.entity.Section;
import app.persistence.entity.SectionBlock;
import app.persistence.entity.SectionFigure;
import app.persistence.entity.Task;
import app.persistence.entity.TaskAnswer;
import app.persistence.repository.ExamRepository;
import app.persistence.repository.SectionBlockRepository;
import app.persistence.repository.SectionFigureRepository;
import app.persistence.repository.SectionRepository;
import app.persistence.repository.TaskAnswerRepository;
import app.persistence.repository.TaskRepository;
import app.storage.StorageService;
import java.nio.charset.StandardCharsets;
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

    private static final String FIGURE_BUCKET = "exam-figures";

    @Autowired CrudService crud;
    @Autowired ExamRepository exams;
    @Autowired SectionRepository sections;
    @Autowired TaskRepository tasks;
    @Autowired SectionBlockRepository blocks;
    @Autowired SectionFigureRepository figures;
    @Autowired TaskAnswerRepository answers;
    @Autowired StorageService storage;

    private Exam seedExamWithContent() {
        Exam exam = new Exam();
        exam.setOwnerId(DefaultUser.ID);
        exam.setSource("manual");
        exam.setTitle("Original");
        exam.setStatus("ready");
        exam.setSolverModel("openai:gpt-5.5");
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

    /** Add a figure block with one figure to a section; stores image bytes when provided. */
    private SectionFigure addFigure(Exam exam, Section section, byte[] bytes) {
        SectionBlock figBlock = new SectionBlock();
        figBlock.setExamId(exam.getId());
        figBlock.setSectionId(section.getId());
        figBlock.setKind("figure");
        figBlock.setContent("");
        figBlock.setPosition(2);
        blocks.save(figBlock);

        SectionFigure fig = new SectionFigure();
        fig.setBlockId(figBlock.getId());
        fig.setPosition(0);
        fig.setSource("upload");
        fig.setCaption("Diagram 1");
        String path = DefaultUser.ID + "/" + exam.getId() + "/" + fig.getId() + ".png";
        fig.setStoragePath(path);
        figures.save(fig);
        if (bytes != null) storage.store(FIGURE_BUCKET, path, bytes);
        return fig;
    }

    @Test
    void duplicateExamDeepCopiesContentAsAFreshDraft() {
        Exam src = seedExamWithContent();
        Section srcSection = sections.findByExamIdOrderByPositionAsc(src.getId()).get(0);
        byte[] imageBytes = "fake-png-bytes".getBytes(StandardCharsets.UTF_8);
        SectionFigure srcFig = addFigure(src, srcSection, imageBytes);

        Exam copy = crud.duplicateExam(src, DefaultUser.ID.toString());

        assertThat(copy.getId()).isNotEqualTo(src.getId());
        assertThat(copy.getTitle()).isEqualTo("Original (Copy)");
        assertThat(copy.getStatus()).isEqualTo("draft");
        assertThat(copy.getOwnerId()).isEqualTo(DefaultUser.ID);
        // Solver model carries over so the copy solves with the same model.
        assertThat(copy.getSolverModel()).isEqualTo("openai:gpt-5.5");

        assertThat(sections.findByExamIdOrderByPositionAsc(copy.getId())).hasSize(1);
        List<SectionBlock> copiedBlocks = blocks.findByExamIdOrderByPositionAsc(copy.getId());
        assertThat(copiedBlocks).hasSize(2); // context + figure block
        List<Task> copiedTasks = tasks.findByExamIdOrderByPositionAsc(copy.getId());
        assertThat(copiedTasks).hasSize(1);
        // Goal ids belong to the source's LGH goals — must NOT be copied.
        assertThat(copiedTasks.get(0).getLearningGoalIds()).isNull();

        // The figure and its image are deep-copied onto the copy's figure block.
        SectionBlock copiedFigBlock = copiedBlocks.stream()
            .filter(b -> "figure".equals(b.getKind())).findFirst().orElseThrow();
        List<SectionFigure> copiedFigures =
            figures.findByBlockIdOrderByPositionAsc(copiedFigBlock.getId());
        assertThat(copiedFigures).hasSize(1);
        SectionFigure copiedFig = copiedFigures.get(0);
        assertThat(copiedFig.getId()).isNotEqualTo(srcFig.getId());
        assertThat(copiedFig.getCaption()).isEqualTo("Diagram 1");
        assertThat(copiedFig.getStoragePath())
            .contains(copy.getId().toString())
            .isNotEqualTo(srcFig.getStoragePath());
        // The bytes were copied to the new path, and the source object is untouched.
        assertThat(storage.download(FIGURE_BUCKET, copiedFig.getStoragePath())).isEqualTo(imageBytes);
        assertThat(storage.download(FIGURE_BUCKET, srcFig.getStoragePath())).isEqualTo(imageBytes);
    }

    @Test
    void duplicateExamSkipsFiguresWhoseSourceBytesAreMissing() {
        Exam src = seedExamWithContent();
        Section srcSection = sections.findByExamIdOrderByPositionAsc(src.getId()).get(0);
        // Figure row exists but its image object was never stored.
        addFigure(src, srcSection, null);

        Exam copy = crud.duplicateExam(src, DefaultUser.ID.toString());

        // The figure block is still copied...
        SectionBlock copiedFigBlock = blocks.findByExamIdOrderByPositionAsc(copy.getId()).stream()
            .filter(b -> "figure".equals(b.getKind())).findFirst().orElseThrow();
        // ...but no dangling figure row is persisted for the missing image.
        assertThat(figures.findByBlockIdOrderByPositionAsc(copiedFigBlock.getId())).isEmpty();
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
