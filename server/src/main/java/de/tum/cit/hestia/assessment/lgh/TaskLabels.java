package de.tum.cit.hestia.assessment.lgh;

import de.tum.cit.hestia.assessment.examination.Examination;
import de.tum.cit.hestia.assessment.examination.ExaminationRepository;
import de.tum.cit.hestia.assessment.taskblock.TaskBlock;
import de.tum.cit.hestia.assessment.taskblock.TaskBlockRepository;
import de.tum.cit.hestia.core.CourseAccess;
import de.tum.cit.hestia.core.DomainModule;
import de.tum.cit.hestia.core.spi.AlignmentTargetLabels;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Names exam tasks in the course's review list: "exam title - task". */
@Component
class TaskLabels implements AlignmentTargetLabels {

    private static final int PROMPT_LENGTH = 60;

    private final TaskBlockRepository tasks;
    private final ExaminationRepository exams;
    private final CourseAccess access;

    TaskLabels(TaskBlockRepository tasks, ExaminationRepository exams, CourseAccess access) {
        this.tasks = tasks;
        this.exams = exams;
        this.access = access;
    }

    @Override
    public DomainModule module() {
        return DomainModule.ASSESSMENT;
    }

    @Override
    public Map<String, String> labels(String targetType, Collection<String> targetIds) {
        Map<String, String> out = new HashMap<>();
        if (!TaskGoalLinks.TARGET_TYPE.equals(targetType)) return out;
        Map<UUID, Examination> examCache = new HashMap<>();
        for (String raw : targetIds) {
            UUID id;
            try {
                id = UUID.fromString(raw);
            } catch (IllegalArgumentException e) {
                continue;
            }
            TaskBlock task = tasks.findById(id).orElse(null);
            if (task == null) continue;
            Examination exam = examCache.computeIfAbsent(task.getExamId(), e -> exams.findById(e).orElse(null));
            if (exam == null || !access.canRead(exam.getCourseId())) continue;
            out.put(raw, exam.getTitle() + " – " + taskName(task));
        }
        return out;
    }

    private String taskName(TaskBlock task) {
        String prompt = task.getPrompt() == null ? "" : task.getPrompt().strip().replaceAll("\s+", " ");
        if (prompt.isEmpty()) return "Aufgabe " + task.getPosition();
        return prompt.length() > PROMPT_LENGTH ? prompt.substring(0, PROMPT_LENGTH) + "…" : prompt;
    }
}
