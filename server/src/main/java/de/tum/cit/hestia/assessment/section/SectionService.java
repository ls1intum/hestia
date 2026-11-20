package de.tum.cit.hestia.assessment.section;

import de.tum.cit.hestia.assessment.examination.Examination;
import de.tum.cit.hestia.assessment.examination.ExaminationRepository;
import de.tum.cit.hestia.assessment.lgh.TaskBlockGoalGenerationService;
import de.tum.cit.hestia.assessment.lgh.TaskGoalLinks;
import de.tum.cit.hestia.assessment.sse.SseHub;
import de.tum.cit.hestia.assessment.taskblock.TaskBlock;
import de.tum.cit.hestia.assessment.taskblock.AIAnswerRepository;
import de.tum.cit.hestia.assessment.taskblock.TaskBlockRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Multi-step section/block operations that must be atomic: position-shifting
 * inserts (ports of the {@code shift_and_insert_*} DB functions), section
 * unconfirm (which also clears the section's AI answers and detaches its goals),
 * and section delete with full child cleanup.
 */
@Service
public class SectionService {

    private final ExaminationRepository exams;
    private final SectionRepository sections;
    private final TaskBlockRepository tasks;
    private final SectionBlockRepository blocks;
    private final AIAnswerRepository answers;
    private final FigureCleanupService figureCleanup;
    private final TaskBlockGoalGenerationService goalGeneration;
    private final TaskGoalLinks goalLinks;
    private final SseHub sse;

    public SectionService(ExaminationRepository exams, SectionRepository sections, TaskBlockRepository tasks,
                          SectionBlockRepository blocks, AIAnswerRepository answers,
                          FigureCleanupService figureCleanup,
                          TaskBlockGoalGenerationService goalGeneration, TaskGoalLinks goalLinks, SseHub sse) {
        this.goalLinks = goalLinks;
        this.exams = exams;
        this.sections = sections;
        this.tasks = tasks;
        this.blocks = blocks;
        this.answers = answers;
        this.figureCleanup = figureCleanup;
        this.goalGeneration = goalGeneration;
        this.sse = sse;
    }

    /** Insert a section at {@code position}, shifting later sections down. */
    @Transactional
    public Section addSection(Section section) {
        sections.shiftSectionsFrom(section.getExamId(), section.getPosition());
        return sections.save(section);
    }

    /** Insert a block at its position, shifting that section's blocks and tasks down. */
    @Transactional
    public SectionBlock addBlock(SectionBlock block) {
        blocks.shiftBlocksInSection(block.getSectionId(), block.getPosition());
        tasks.shiftTaskBlocksInSection(block.getExamId(), block.getSectionId(), block.getPosition());
        return blocks.save(block);
    }

    /** Delete one block and remove its stored figure objects after commit. */
    @Transactional
    public void deleteBlock(SectionBlock block) {
        figureCleanup.scheduleForBlock(block.getId());
        blocks.delete(block);
    }

    /** Delete a section's blocks and remove their stored figure objects after commit. */
    @Transactional
    public void deleteBlocks(UUID examId, UUID sectionId) {
        figureCleanup.scheduleForSection(examId, sectionId);
        blocks.deleteByExamIdAndSectionId(examId, sectionId);
    }

    /**
     * Clear a section's confirmation, drop the AI answers for its tasks, and
     * detach their learning goals: the links go now, and the goals that were derived for the
     * now-retracted content are removed in the background (best-effort).
     */
    @Transactional
    public void unconfirmSection(Section section) {
        List<UUID> taskIds = new ArrayList<>();
        List<TaskBlock> sectionTaskBlocks = tasks.findBySectionIdOrderByPositionAsc(section.getId());
        for (TaskBlock t : sectionTaskBlocks) taskIds.add(t.getId());
        if (!taskIds.isEmpty()) answers.deleteByTaskIdIn(taskIds);
        section.setConfirmedAt(null);
        sections.save(section);

        List<UUID> derived = goalGeneration.detachGoals(taskIds);
        if (!derived.isEmpty()) {
            try {
                goalGeneration.dispatchCleanup(derived);
            } catch (RuntimeException ignored) {}
        }
        // Answers were dropped regardless of whether goals existed — always
        // tell SSE subscribers the tasks changed.
        sse.tasksUpdated(section.getExamId());
    }

    /**
     * Delete a section with full cleanup, server-side: unconfirm first when
     * needed (drops AI answers + detaches goals), then remove its tasks
     * and blocks along with the section — the frontend is not trusted to call
     * the delete-by-section endpoints beforehand.
     */
    @Transactional
    public void deleteSection(Section section) {
        figureCleanup.scheduleForSection(section.getExamId(), section.getId());
        if (section.getConfirmedAt() != null) {
            unconfirmSection(section);
        }
        goalLinks.unlinkAll(tasks.findBySectionIdOrderByPositionAsc(section.getId()).stream().map(TaskBlock::getId).toList());
        tasks.deleteByExamIdAndSectionId(section.getExamId(), section.getId());
        blocks.deleteByExamIdAndSectionId(section.getExamId(), section.getId());
        sections.delete(section);
    }
}
