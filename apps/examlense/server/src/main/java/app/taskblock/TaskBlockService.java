package app.taskblock;

import app.section.SectionBlockRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Multi-step task operations that must be atomic: the position-shifting insert
 * (port of the {@code shift_and_insert_task} DB function), which shifts both the
 * same-bucket tasks and the section's blocks down to open a slot.
 */
@Service
public class TaskBlockService {

    private final TaskBlockRepository tasks;
    private final SectionBlockRepository blocks;

    public TaskBlockService(TaskBlockRepository tasks, SectionBlockRepository blocks) {
        this.tasks = tasks;
        this.blocks = blocks;
    }

    /** Insert a task at its position, shifting same-bucket tasks (and section blocks) down. */
    @Transactional
    public TaskBlock addTaskBlock(TaskBlock task) {
        if (task.getSectionId() != null) {
            tasks.shiftTaskBlocksInSection(task.getExamId(), task.getSectionId(), task.getPosition());
            blocks.shiftBlocksInSection(task.getSectionId(), task.getPosition());
        } else {
            tasks.shiftTaskBlocksNullSection(task.getExamId(), task.getPosition());
        }
        return tasks.save(task);
    }
}
