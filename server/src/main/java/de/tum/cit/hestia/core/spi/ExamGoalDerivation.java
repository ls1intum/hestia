package de.tum.cit.hestia.core.spi;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Derives learning goals from exam tasks and stores them in a course. Implemented by
 * {@code materials} (the derivation is its model logic); replaces the HTTP call to LearningGoalHub's
 * {@code POST /api/courses/{id}/exam-tasks/learning-goals}. Runs one model call per task, so callers
 * use a background thread. Does not deduplicate: deriving the same tasks twice stores the goals twice.
 */
public interface ExamGoalDerivation {

    /** @throws NoSuchElementException if the course does not exist */
    List<TaskGoals> derive(long courseId, List<ExamTask> tasks);

    /** One ordered exam block: shared context or a task. */
    record ExamTask(String blockId, String blockType, String taskType, String description) {}

    record TaskGoals(String blockId, List<GoalDirectory.Goal> goals) {}
}
