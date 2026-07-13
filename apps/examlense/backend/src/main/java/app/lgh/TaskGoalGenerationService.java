package app.lgh;

import app.persistence.entity.Exam;
import app.persistence.entity.Section;
import app.persistence.entity.SectionBlock;
import app.persistence.entity.Task;
import app.persistence.entity.TaskOption;
import app.persistence.repository.ExamRepository;
import app.persistence.repository.SectionBlockRepository;
import app.persistence.repository.SectionRepository;
import app.persistence.repository.TaskRepository;
import app.sse.SseHub;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Derives learning goals for a confirmed section's tasks via LearningGoalHub.
 *
 * Runs asynchronously on the {@code lghExecutor} pool, triggered from the
 * section-confirm endpoint. Deliberately decoupled from solving: a goal-run
 * failure must never fail (or even delay) solving, and vice versa. Guarded by
 * its own CAS lock ({@code sections.goals_started_at}) because LGH does not
 * dedup — a duplicate POST would create every goal twice.
 *
 * Lifecycle: on each run the goals previously stored on the section's tasks
 * are deleted in LGH first (re-confirm case), then regenerated from the
 * section's current context blocks + tasks.
 */
@Service
public class TaskGoalGenerationService {

    private static final Logger log = LoggerFactory.getLogger(TaskGoalGenerationService.class);
    private static final long GOALS_LOCK_TTL_SECONDS = 600;

    private final ExamRepository examRepository;
    private final SectionRepository sectionRepository;
    private final SectionBlockRepository sectionBlockRepository;
    private final TaskRepository taskRepository;
    private final LearningGoalHubClient client;
    private final TransactionTemplate txTemplate;
    private final SseHub sse;

    public TaskGoalGenerationService(
        ExamRepository examRepository,
        SectionRepository sectionRepository,
        SectionBlockRepository sectionBlockRepository,
        TaskRepository taskRepository,
        LearningGoalHubClient client,
        PlatformTransactionManager txManager,
        SseHub sse
    ) {
        this.examRepository = examRepository;
        this.sectionRepository = sectionRepository;
        this.sectionBlockRepository = sectionBlockRepository;
        this.taskRepository = taskRepository;
        this.client = client;
        this.txTemplate = new TransactionTemplate(txManager);
        this.sse = sse;
    }

    /** Fire-and-forget entry point called after a section is confirmed. */
    @Async("lghExecutor")
    public void dispatchGenerate(UUID examId, UUID sectionId) {
        try {
            generate(examId, sectionId);
        } catch (RuntimeException e) {
            log.warn("Learning-goal generation for section {} failed: {}", sectionId, e.getMessage());
        }
    }

    /** Best-effort deletion of goals we generated (unconfirm cleanup). */
    @Async("lghExecutor")
    public void dispatchCleanup(long courseId, List<Long> goalIds) {
        for (Long goalId : goalIds) {
            try {
                client.deleteGoal(courseId, goalId);
            } catch (RuntimeException e) {
                log.warn("Could not delete LGH goal {} of course {}: {}", goalId, courseId, e.getMessage());
            }
        }
    }

    private void generate(UUID examId, UUID sectionId) {
        Exam exam = examRepository.findById(examId).orElse(null);
        if (exam == null || exam.getLghCourseId() == null) {
            log.debug("Skipping goal generation for section {}: no LGH course linked", sectionId);
            return;
        }
        long courseId = exam.getLghCourseId();

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime cutoff = now.minusSeconds(GOALS_LOCK_TTL_SECONDS);
        if (sectionRepository.acquireGoalsLock(sectionId, examId, now, cutoff) == 0) {
            log.debug("Goal generation for section {} already running", sectionId);
            return;
        }

        try {
            if (!isConfirmed(sectionId)) return; // unconfirmed while queued

            List<Task> tasks = taskRepository.findByExamIdAndSectionIdOrderByPositionAsc(examId, sectionId);
            if (tasks.isEmpty()) return;

            // Re-confirm case: remove the goals from the previous run so LGH
            // doesn't accumulate duplicates, then detach them from our tasks.
            for (Task t : tasks) {
                List<Long> old = t.getLearningGoalIds();
                if (old != null) {
                    for (Long goalId : old) {
                        try {
                            client.deleteGoal(courseId, goalId);
                        } catch (RuntimeException e) {
                            log.warn("Could not delete stale LGH goal {}: {}", goalId, e.getMessage());
                        }
                    }
                }
            }
            txTemplate.executeWithoutResult(s -> {
                for (Task t : tasks) t.setLearningGoalIds(null);
                taskRepository.saveAll(tasks);
            });

            List<LghDtos.ExamBlock> blocks = buildBlocks(sectionId, tasks);
            List<LghDtos.ExamTaskGoals> result = client.generateExamGoals(courseId, blocks);

            // If the user unconfirmed mid-run the content may change again —
            // drop the result and compensate by deleting the fresh goals.
            if (!isConfirmed(sectionId)) {
                for (LghDtos.ExamTaskGoals entry : result) {
                    for (LghDtos.LearningGoal g : entry.goals()) {
                        try {
                            client.deleteGoal(courseId, g.id());
                        } catch (RuntimeException e) {
                            log.warn("Could not delete orphaned LGH goal {}: {}", g.id(), e.getMessage());
                        }
                    }
                }
                return;
            }

            Map<String, Task> byId = new HashMap<>();
            for (Task t : tasks) byId.put(t.getId().toString(), t);
            txTemplate.executeWithoutResult(s -> {
                List<Task> changed = new ArrayList<>();
                for (LghDtos.ExamTaskGoals entry : result) {
                    Task t = byId.get(entry.blockId());
                    if (t == null || entry.goals() == null) continue;
                    t.setLearningGoalIds(entry.goals().stream().map(LghDtos.LearningGoal::id).toList());
                    changed.add(t);
                }
                taskRepository.saveAll(changed);
            });
            log.info("Stored learning goals for {} task(s) in section {}", result.size(), sectionId);
            sse.tasksUpdated(examId); // SseHub never throws
        } finally {
            try {
                sectionRepository.releaseGoalsLock(sectionId);
            } catch (RuntimeException ignored) {}
        }
    }

    private boolean isConfirmed(UUID sectionId) {
        Section s = sectionRepository.findById(sectionId).orElse(null);
        return s != null && s.getConfirmedAt() != null;
    }

    /**
     * Interleave the section's context blocks and tasks by position — LGH
     * attaches every context block to the task blocks after it, so order
     * matters. Figure blocks and blank context are skipped.
     */
    private List<LghDtos.ExamBlock> buildBlocks(UUID sectionId, List<Task> tasks) {
        record Positioned(int position, LghDtos.ExamBlock block) {}
        List<Positioned> items = new ArrayList<>();
        for (SectionBlock b : sectionBlockRepository.findBySectionIdOrderByPositionAsc(sectionId)) {
            if (!"context".equals(b.getKind())) continue;
            if (b.getContent() == null || b.getContent().isBlank()) continue;
            items.add(new Positioned(b.getPosition(),
                new LghDtos.ExamBlock(b.getId().toString(), "context", null, b.getContent())));
        }
        for (Task t : tasks) {
            items.add(new Positioned(t.getPosition(),
                new LghDtos.ExamBlock(t.getId().toString(), "task", taskTypeLabel(t.getType()), taskDescription(t))));
        }
        items.sort((a, b) -> Integer.compare(a.position(), b.position()));
        return items.stream().map(Positioned::block).toList();
    }

    private static String taskTypeLabel(String type) {
        return switch (type == null ? "" : type) {
            case "single_choice" -> "singleChoice";
            case "multiple_choice" -> "multipleChoice";
            case "text" -> "freeText";
            default -> type;
        };
    }

    /** Prompt plus the option texts, so LGH's LLM sees the full task. */
    private static String taskDescription(Task t) {
        StringBuilder sb = new StringBuilder(t.getPrompt() == null ? "" : t.getPrompt());
        if (t.getOptions() != null && !t.getOptions().isEmpty()) {
            sb.append("\n\nOptions:");
            for (TaskOption o : t.getOptions()) {
                sb.append("\n- ").append(o.text());
            }
        }
        return sb.toString();
    }
}
