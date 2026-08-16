package app.task;

import app.section.SectionBlockRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Multi-step task operations that must be atomic: the position-shifting insert
 * (port of the {@code shift_and_insert_task} DB function), which shifts both the
 * same-bucket tasks and the section's blocks down to open a slot.
 */
@Service
public class TaskService {

    private final TaskRepository tasks;
    private final SectionBlockRepository blocks;

    public TaskService(TaskRepository tasks, SectionBlockRepository blocks) {
        this.tasks = tasks;
        this.blocks = blocks;
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
}
