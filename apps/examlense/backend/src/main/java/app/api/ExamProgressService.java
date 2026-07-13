package app.api;

import app.persistence.entity.Task;
import app.persistence.entity.TaskGrade;
import app.persistence.entity.TaskOption;
import app.persistence.repository.TaskAnswerRepository;
import app.persistence.repository.TaskGradeRepository;
import app.persistence.repository.TaskRepository;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Computes the per-exam progress counts shown in the dashboard table's
 * "Progress" column. Everything is derived from three bulk queries (tasks,
 * grades, answers) grouped in memory, so listing N exams stays at 3 queries
 * total rather than N+1.
 *
 * <p>The "graded" definition mirrors the frontend grading logic in
 * {@code src/lib/grading.ts} (see {@link #isTaskGraded}) so the table's grading
 * percentage matches what the results view resolves.
 */
@Service
public class ExamProgressService {

    /** Counts backing one exam's progress bar. All are relative to {@code taskCount}. */
    public record Counts(long taskCount, long scoredCount, long answeredCount, long gradedCount) {
        static final Counts EMPTY = new Counts(0, 0, 0, 0);
    }

    private final TaskRepository tasks;
    private final TaskGradeRepository grades;
    private final TaskAnswerRepository answers;

    public ExamProgressService(TaskRepository tasks, TaskGradeRepository grades, TaskAnswerRepository answers) {
        this.tasks = tasks;
        this.grades = grades;
        this.answers = answers;
    }

    /** Per-exam counts for the given exam ids; missing exams map to {@link Counts#EMPTY}. */
    public Map<UUID, Counts> countsFor(List<UUID> examIds) {
        Map<UUID, Counts> out = new HashMap<>();
        if (examIds == null || examIds.isEmpty()) return out;

        // task_id -> grade (score-bearing wins so a scored grade isn't masked by a null one)
        Map<UUID, TaskGrade> gradeByTask = new HashMap<>();
        for (TaskGrade g : grades.findByExamIdIn(examIds)) {
            gradeByTask.merge(g.getTaskId(), g, (a, b) -> a.getScore() != null ? a : b);
        }
        // task_ids that have at least one AI answer
        Set<UUID> answeredTasks = new HashSet<>();
        for (var a : answers.findByExamIdIn(examIds)) {
            answeredTasks.add(a.getTaskId());
        }

        // Accumulate per exam in one pass over tasks.
        Map<UUID, long[]> acc = new HashMap<>(); // [task, scored, answered, graded]
        for (Task t : tasks.findByExamIdIn(examIds)) {
            long[] c = acc.computeIfAbsent(t.getExamId(), k -> new long[4]);
            boolean hasAnswer = answeredTasks.contains(t.getId());
            c[0]++;
            if (t.getPoints() != null && t.getPoints().compareTo(BigDecimal.ZERO) > 0) c[1]++;
            if (hasAnswer) c[2]++;
            if (isTaskGraded(t, gradeByTask.get(t.getId()), hasAnswer)) c[3]++;
        }

        for (UUID id : examIds) {
            long[] c = acc.get(id);
            out.put(id, c == null ? Counts.EMPTY : new Counts(c[0], c[1], c[2], c[3]));
        }
        return out;
    }

    /**
     * A task counts as graded when it has a persisted score, or it is an
     * auto-resolvable choice task (mirrors {@code effectiveScore}/
     * {@code autoGradeChoiceTask} in {@code src/lib/grading.ts}): not a text
     * task, positive points, at least one correct option defined, and an AI
     * answer to grade against.
     */
    static boolean isTaskGraded(Task task, TaskGrade grade, boolean hasAnswer) {
        if (grade != null && grade.getScore() != null) return true;
        if ("text".equals(task.getType())) return false;
        if (task.getPoints() == null || task.getPoints().compareTo(BigDecimal.ZERO) <= 0) return false;
        if (!hasAnswer) return false;
        List<TaskOption> options = task.getOptions();
        if (options == null) return false;
        return options.stream().anyMatch(TaskOption::isCorrect);
    }
}
