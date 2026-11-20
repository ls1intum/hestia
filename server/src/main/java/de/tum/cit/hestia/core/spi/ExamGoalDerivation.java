package de.tum.cit.hestia.core.spi;

import de.tum.cit.hestia.core.GoalInfo;
import java.util.List;
import java.util.UUID;

/**
 * Derives learning goals from exam tasks and stores them in a course of {@code core}. Implemented
 * by {@code materials} (the derivation is its model logic). Runs one model call per task, so
 * callers use a background thread. Does not deduplicate: deriving the same tasks twice stores the
 * goals twice. The caller needs {@code EDITOR} on the course.
 */
public interface ExamGoalDerivation {

    /** @throws de.tum.cit.hestia.core.CourseNotFoundException if the course is not readable */
    List<TaskGoals> derive(UUID courseId, List<ExamTask> tasks);

    /** One ordered exam block: shared context or a task. */
    record ExamTask(String blockId, String blockType, String taskType, String description) {}

    /** The goals stored for one task block. */
    record TaskGoals(String blockId, List<GoalInfo> goals) {}
}
