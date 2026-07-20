package app.exam;

import app.section.Section;
import app.section.SectionBlock;
import app.section.SectionBlockRepository;
import app.section.SectionFigure;
import app.section.SectionFigureRepository;
import app.section.SectionRepository;
import app.storage.StorageService;
import app.task.Task;
import app.task.TaskRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exam-level operations that must be atomic — currently full exam duplication
 * (exam → sections → blocks → tasks → figures, with fresh storage objects).
 * Walks the child aggregates' repositories directly; that cross-slice reach is
 * inherent to a deep-copy and kept explicit here rather than hidden in a shared
 * god-service.
 */
@Service
public class ExamService {

    private static final String PDF_BUCKET = "exam-pdfs";
    private static final String FIGURE_BUCKET = "exam-figures";

    private final ExamRepository exams;
    private final SectionRepository sections;
    private final TaskRepository tasks;
    private final SectionBlockRepository blocks;
    private final SectionFigureRepository figures;
    private final StorageService storage;

    public ExamService(ExamRepository exams, SectionRepository sections, TaskRepository tasks,
                       SectionBlockRepository blocks, SectionFigureRepository figures,
                       StorageService storage) {
        this.exams = exams;
        this.sections = sections;
        this.tasks = tasks;
        this.blocks = blocks;
        this.figures = figures;
        this.storage = storage;
    }

    /**
     * Deep-copy an exam: exam → sections → blocks → tasks → figures, remapping
     * section and block ids. The source PDF and each figure image are copied to
     * new storage objects — sharing a path would mean deleting either exam breaks
     * the other's files. Figure copies are best-effort: an image whose source
     * object is missing is skipped rather than left as a dangling row.
     */
    @Transactional
    public Exam duplicateExam(Exam src, String userId) {
        Exam copy = new Exam();
        copy.setOwnerId(UUID.fromString(userId));
        copy.setTitle((src.getTitle() == null ? "" : src.getTitle()) + " (Copy)");
        copy.setCourse(src.getCourse());
        copy.setLanguage(src.getLanguage());
        copy.setSource(src.getSource());
        copy.setStatus("draft");
        copy.setSolverModel(src.getSolverModel());
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

        Map<UUID, UUID> blockIdMap = new HashMap<>();
        List<SectionBlock> newBlocks = new ArrayList<>();
        for (SectionBlock b : blocks.findByExamIdOrderByPositionAsc(src.getId())) {
            SectionBlock nb = new SectionBlock();
            nb.setExamId(copy.getId());
            nb.setSectionId(b.getSectionId() == null ? null : sectionIdMap.get(b.getSectionId()));
            nb.setPosition(b.getPosition());
            nb.setContent(b.getContent());
            nb.setKind(b.getKind());
            newBlocks.add(nb);
            blockIdMap.put(b.getId(), nb.getId());
        }
        blocks.saveAll(newBlocks);
        copyFigures(blockIdMap, userId, copy.getId());

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
     * Copy each source block's figures onto the corresponding new block. Both the
     * {@link SectionFigure} row and its image object get fresh ids/paths owned by
     * the new exam. Best-effort: a figure whose source object is missing or fails
     * to copy is skipped, so no row is persisted pointing at bytes that don't
     * exist. The rest of the duplication still succeeds.
     */
    private void copyFigures(Map<UUID, UUID> blockIdMap, String userId, UUID newExamId) {
        List<SectionFigure> newFigures = new ArrayList<>();
        for (Map.Entry<UUID, UUID> e : blockIdMap.entrySet()) {
            for (SectionFigure f : figures.findByBlockIdOrderByPositionAsc(e.getKey())) {
                SectionFigure nf = new SectionFigure();
                nf.setBlockId(e.getValue());
                nf.setPosition(f.getPosition());
                nf.setCaption(f.getCaption());
                nf.setSource(f.getSource());
                String ext = f.getStoragePath().contains(".")
                    ? f.getStoragePath().substring(f.getStoragePath().lastIndexOf('.'))
                    : "";
                String newPath = userId + "/" + newExamId + "/" + nf.getId() + ext;
                if (!copyFigureObject(f.getStoragePath(), newPath)) continue;
                nf.setStoragePath(newPath);
                newFigures.add(nf);
            }
        }
        figures.saveAll(newFigures);
    }

    /**
     * Best-effort copy of a figure image into a path owned by the new exam.
     * Returns {@code true} only when the bytes were copied; a missing source
     * object or any I/O error returns {@code false} so the caller can skip it.
     */
    private boolean copyFigureObject(String sourcePath, String newPath) {
        try {
            byte[] bytes = storage.download(FIGURE_BUCKET, sourcePath);
            if (bytes == null) return false;
            storage.store(FIGURE_BUCKET, newPath, bytes);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
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
