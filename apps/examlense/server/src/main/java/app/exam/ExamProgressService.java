package app.exam;

import app.task.Task;
import app.grading.TaskGrade;
import app.task.TaskOption;
import app.task.TaskAnswerRepository;
import app.grading.TaskGradeRepository;
import app.task.TaskRepository;
import app.section.Section;
import app.section.SectionRepository;
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

    /**
     * Counts backing one exam's progress bar. Task-relative counts (scored/
     * answered/graded) are relative to {@code taskCount}; the section counts
     * back the dashboard's "prepare" step (confirmed / total sections).
     */
    public record Counts(long taskCount, long scoredCount, long answeredCount, long gradedCount,
                         long sectionCount, long confirmedSectionCount) {
        static final Counts EMPTY = new Counts(0, 0, 0, 0, 0, 0);
    }

    private final TaskRepository tasks;
    private final TaskGradeRepository grades;
    private final TaskAnswerRepository answers;
    private final SectionRepository sections;

    public ExamProgressService(TaskRepository tasks, TaskGradeRepository grades,
                               TaskAnswerRepository answers, SectionRepository sections) {
        this.tasks = tasks;
        this.grades = grades;
        this.answers = answers;
        this.sections = sections;
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

        // Section totals + confirmed count per exam (one bulk query).
        Map<UUID, long[]> secAcc = new HashMap<>(); // [sections, confirmed]
        for (Section s : sections.findByExamIdIn(examIds)) {
            long[] c = secAcc.computeIfAbsent(s.getExamId(), k -> new long[2]);
            c[0]++;
            if (s.getConfirmedAt() != null) c[1]++;
        }

        for (UUID id : examIds) {
            long[] c = acc.get(id);
            long[] s = secAcc.get(id);
            if (c == null && s == null) {
                out.put(id, Counts.EMPTY);
                continue;
            }
            long taskCount = c == null ? 0 : c[0];
            long scored = c == null ? 0 : c[1];
            long answered = c == null ? 0 : c[2];
            long graded = c == null ? 0 : c[3];
            long secTotal = s == null ? 0 : s[0];
            long secConfirmed = s == null ? 0 : s[1];
            out.put(id, new Counts(taskCount, scored, answered, graded, secTotal, secConfirmed));
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
