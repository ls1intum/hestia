package app.api;

import app.lgh.TaskGoalGenerationService;
import app.persistence.entity.Exam;
import app.persistence.entity.Section;
import app.persistence.entity.SectionBlock;
import app.persistence.entity.Task;
import app.persistence.repository.ExamRepository;
import app.persistence.repository.SectionBlockRepository;
import app.persistence.repository.SectionRepository;
import app.persistence.repository.TaskAnswerRepository;
import app.persistence.repository.TaskRepository;
import app.sse.SseHub;
import app.storage.StorageService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Multi-step CRUD operations that must be atomic: position-shifting inserts
 * (ports of the {@code shift_and_insert_*} DB functions), full exam duplication,
 * and section unconfirm (which also clears the section's AI answers).
 */
@Service
public class CrudService {

    private static final String PDF_BUCKET = "exam-pdfs";

    private final ExamRepository exams;
    private final SectionRepository sections;
    private final TaskRepository tasks;
    private final SectionBlockRepository blocks;
    private final TaskAnswerRepository answers;
    private final TaskGoalGenerationService goalGeneration;
    private final SseHub sse;
    private final StorageService storage;

    public CrudService(ExamRepository exams, SectionRepository sections, TaskRepository tasks,
                       SectionBlockRepository blocks, TaskAnswerRepository answers,
                       TaskGoalGenerationService goalGeneration, SseHub sse, StorageService storage) {
        this.exams = exams;
        this.sections = sections;
        this.tasks = tasks;
        this.blocks = blocks;
        this.answers = answers;
        this.goalGeneration = goalGeneration;
        this.sse = sse;
        this.storage = storage;
    }

    /** Insert a section at {@code position}, shifting later sections down. */
    @Transactional
    public Section addSection(Section section) {
        sections.shiftSectionsFrom(section.getExamId(), section.getPosition());
        return sections.save(section);
    }

    /** Insert a task at its position, shifting same-bucket tasks (and section blocks) down. */
    @Transactional
    public Task addTask(Task task) {
        if (task.getSectionId() != null) {
            tasks.shiftTasksInSection(task.getExamId(), task.getSectionId(), task.getPosition());
            blocks.shiftBlocksInSection(task.getSectionId(), task.getPosition());
        } else {
            tasks.shiftTasksNullSection(task.getExamId(), task.getPosition());
        }
        return tasks.save(task);
    }

    /** Insert a block at its position, shifting that section's blocks and tasks down. */
    @Transactional
    public SectionBlock addBlock(SectionBlock block) {
        blocks.shiftBlocksInSection(block.getSectionId(), block.getPosition());
        tasks.shiftTasksInSection(block.getExamId(), block.getSectionId(), block.getPosition());
        return blocks.save(block);
    }

    /**
     * Clear a section's confirmation, drop the AI answers for its tasks, and
     * detach the LGH-derived learning goals (deleted in LGH async best-effort
     * — the goals were generated for the now-retracted content).
     */
    @Transactional
    public void unconfirmSection(Section section) {
        List<UUID> taskIds = new ArrayList<>();
        List<Long> goalIds = new ArrayList<>();
        List<Task> sectionTasks = tasks.findBySectionIdOrderByPositionAsc(section.getId());
        for (Task t : sectionTasks) {
            taskIds.add(t.getId());
            if (t.getLearningGoalIds() != null) {
                goalIds.addAll(t.getLearningGoalIds());
                t.setLearningGoalIds(null);
            }
        }
        if (!taskIds.isEmpty()) answers.deleteByTaskIdIn(taskIds);
        tasks.saveAll(sectionTasks);
        section.setConfirmedAt(null);
        sections.save(section);

        if (!goalIds.isEmpty()) {
            Exam exam = exams.findById(section.getExamId()).orElse(null);
            if (exam != null && exam.getLghCourseId() != null) {
                try {
                    goalGeneration.dispatchCleanup(exam.getLghCourseId(), goalIds);
                } catch (RuntimeException ignored) {}
            }
        }
        // Answers were dropped regardless of whether goals existed — always
        // tell SSE subscribers the tasks changed.
        sse.tasksUpdated(section.getExamId());
    }

    /**
     * Delete a section with full cleanup, server-side: unconfirm first when
     * needed (drops AI answers + detaches LGH goals), then remove its tasks
     * and blocks along with the section — the frontend is not trusted to call
     * the delete-by-section endpoints beforehand.
     */
    @Transactional
    public void deleteSection(Section section) {
        if (section.getConfirmedAt() != null) {
            unconfirmSection(section);
        }
        tasks.deleteByExamIdAndSectionId(section.getExamId(), section.getId());
        blocks.deleteByExamIdAndSectionId(section.getExamId(), section.getId());
        sections.delete(section);
    }

    /**
     * Deep-copy an exam: exam → sections → blocks → tasks, remapping section ids.
     * Figures are intentionally not copied (matches the prior frontend behavior).
     * The source PDF is copied to a new storage object — sharing the path would
     * mean deleting either exam breaks the other's file.
     */
    @Transactional
    public Exam duplicateExam(Exam src, String userId) {
        Exam copy = new Exam();
        copy.setOwnerId(UUID.fromString(userId));
        copy.setTitle((src.getTitle() == null ? "" : src.getTitle()) + " (Copy)");
        copy.setCourse(src.getCourse());
        copy.setSemester(src.getSemester());
        copy.setInstructorName(src.getInstructorName());
        copy.setTotalPoints(src.getTotalPoints());
        copy.setLanguage(src.getLanguage());
        copy.setSource(src.getSource());
        copy.setStatus("draft");
        copy.setLghCourseId(src.getLghCourseId());
        exams.save(copy);
        copy.setSourceFileUrl(copyPdf(src.getSourceFileUrl(), userId, copy.getId()));

        Map<UUID, UUID> sectionIdMap = new HashMap<>();
        List<Section> newSections = new ArrayList<>();
        for (Section s : sections.findByExamIdOrderByPositionAsc(src.getId())) {
            Section ns = new Section();
            ns.setExamId(copy.getId());
            ns.setName(s.getName());
            ns.setPosition(s.getPosition());
            newSections.add(ns);
            sectionIdMap.put(s.getId(), ns.getId());
        }
        sections.saveAll(newSections);

        List<SectionBlock> newBlocks = new ArrayList<>();
        for (SectionBlock b : blocks.findByExamIdOrderByPositionAsc(src.getId())) {
            SectionBlock nb = new SectionBlock();
            nb.setExamId(copy.getId());
            nb.setSectionId(b.getSectionId() == null ? null : sectionIdMap.get(b.getSectionId()));
            nb.setPosition(b.getPosition());
            nb.setContent(b.getContent());
            nb.setKind(b.getKind());
            newBlocks.add(nb);
        }
        blocks.saveAll(newBlocks);

        List<Task> newTasks = new ArrayList<>();
        for (Task t : tasks.findByExamIdOrderByPositionAsc(src.getId())) {
            Task nt = new Task();
            nt.setExamId(copy.getId());
            nt.setSectionId(t.getSectionId() == null ? null : sectionIdMap.get(t.getSectionId()));
            nt.setSection(t.getSection());
            nt.setPosition(t.getPosition());
            nt.setType(t.getType());
            nt.setPrompt(t.getPrompt());
            nt.setOptions(t.getOptions());
            nt.setReferenceAnswer(t.getReferenceAnswer());
            nt.setPoints(t.getPoints());
            nt.setParseConfidence(t.getParseConfidence());
            // Goal ids are NOT copied: they identify LGH goals owned by the
            // source exam's sections — regenerated when the copy is confirmed.
            newTasks.add(nt);
        }
        tasks.saveAll(newTasks);

        return exams.save(copy);
    }

    /**
     * Best-effort copy of the source PDF into a path owned by the new exam.
     * Returns the new path, or null when there is nothing to copy (or the
     * source object is gone) — the copy is still usable without its PDF.
     */
    private String copyPdf(String sourcePath, String userId, UUID newExamId) {
        if (sourcePath == null || sourcePath.isBlank()) return null;
        try {
            byte[] bytes = storage.download(PDF_BUCKET, sourcePath);
            if (bytes == null) return null;
            String ext = sourcePath.contains(".")
                ? sourcePath.substring(sourcePath.lastIndexOf('.'))
                : ".pdf";
            String newPath = userId + "/" + newExamId + ext;
            storage.store(PDF_BUCKET, newPath, bytes);
            return newPath;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
