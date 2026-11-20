package de.tum.cit.hestia.assessment.lgh;

import de.tum.cit.hestia.assessment.examination.Examination;
import de.tum.cit.hestia.core.AlignmentApi;
import de.tum.cit.hestia.core.DomainModule;
import de.tum.cit.hestia.core.GoalInfo;
import de.tum.cit.hestia.core.GoalOrigin;
import de.tum.cit.hestia.core.LearningGoalApi;
import de.tum.cit.hestia.core.spi.ExamGoalDerivation;
import de.tum.cit.hestia.assessment.section.Section;
import de.tum.cit.hestia.assessment.section.SectionBlock;
import de.tum.cit.hestia.assessment.taskblock.TaskBlock;
import de.tum.cit.hestia.assessment.taskblock.AnswerOption;
import de.tum.cit.hestia.assessment.examination.ExaminationRepository;
import de.tum.cit.hestia.assessment.section.SectionBlockRepository;
import de.tum.cit.hestia.assessment.section.SectionRepository;
import de.tum.cit.hestia.assessment.taskblock.TaskBlockRepository;
import de.tum.cit.hestia.assessment.sse.SseHub;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Derives learning goals for a confirmed section's tasks and links them to the tasks.
 *
 * Runs asynchronously on the {@code lghExecutor} pool, triggered from the
 * section-confirm endpoint. Deliberately decoupled from solving: a goal-run
 * failure must never fail (or even delay) solving, and vice versa. Guarded by
 * its own CAS lock ({@code sections.goals_started_at}) because the derivation does not
 * deduplicate — running it twice would store every goal twice.
 *
 * Lifecycle: on each run the goals previously derived for the section's tasks (origin EXAM,
 * created via assessment) are unlinked and removed from the course first (re-confirm case), then
 * regenerated from the section's current context blocks + tasks into the exam's course.
 */
@Service
public class TaskBlockGoalGenerationService {

    private static final Logger log = LoggerFactory.getLogger(TaskBlockGoalGenerationService.class);
    private static final long GOALS_LOCK_TTL_SECONDS = 600;

    private final ExaminationRepository examRepository;
    private final SectionRepository sectionRepository;
    private final SectionBlockRepository sectionBlockRepository;
    private final TaskBlockRepository taskRepository;
    private final ExamGoalDerivation derivation;
    private final TaskGoalLinks goalLinks;
    private final LearningGoalApi goalApi;
    private final AlignmentApi alignment;
    private final SseHub sse;

    public TaskBlockGoalGenerationService(
        ExaminationRepository examRepository,
        SectionRepository sectionRepository,
        SectionBlockRepository sectionBlockRepository,
        TaskBlockRepository taskRepository,
        ExamGoalDerivation derivation,
        TaskGoalLinks goalLinks,
        LearningGoalApi goalApi,
        AlignmentApi alignment,
        SseHub sse
    ) {
        this.examRepository = examRepository;
        this.sectionRepository = sectionRepository;
        this.sectionBlockRepository = sectionBlockRepository;
        this.taskRepository = taskRepository;
        this.derivation = derivation;
        this.goalLinks = goalLinks;
        this.goalApi = goalApi;
        this.alignment = alignment;
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

    /** Best-effort removal of goals we derived (unconfirm cleanup). */
    @Async("lghExecutor")
    public void dispatchCleanup(List<UUID> goalIds) {
        removeGoals(goalIds);
    }

    /**
     * Unlinks the tasks from all their goals and returns the goals that were derived for them
     * (origin EXAM, created via assessment) and are linked to nothing else, for the caller to
     * remove. Goals the user wrote or linked elsewhere stay.
     */
    public List<UUID> detachGoals(Collection<UUID> taskIds) {
        Set<UUID> candidates = new LinkedHashSet<>();
        for (UUID taskId : taskIds) candidates.addAll(goalLinks.linkedGoalIds(taskId));
        goalLinks.unlinkAll(taskIds);
        if (candidates.isEmpty()) return List.of();
        List<UUID> derived = new ArrayList<>();
        for (GoalInfo g : goalApi.goalsById(candidates)) {
            if (g.removed() || g.origin() != GoalOrigin.EXAM || g.createdVia() != DomainModule.ASSESSMENT) continue;
            if (alignment.linksOfGoal(g.id()).isEmpty()) derived.add(g.id());
        }
        return derived;
    }

    private void removeGoals(Collection<UUID> goalIds) {
        for (UUID goalId : goalIds) {
            try {
                goalApi.remove(goalId);
            } catch (RuntimeException e) {
                log.warn("Could not remove derived goal {}: {}", goalId, e.getMessage());
            }
        }
    }

    private void generate(UUID examId, UUID sectionId) {
        Examination exam = examRepository.findById(examId).orElse(null);
        if (exam == null) {
            log.debug("Skipping goal generation for section {}: the exam no longer exists", sectionId);
            return;
        }
        UUID courseId = exam.getCourseId();

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime cutoff = now.minusSeconds(GOALS_LOCK_TTL_SECONDS);
        if (sectionRepository.acquireGoalsLock(sectionId, examId, now, cutoff) == 0) {
            log.debug("Goal generation for section {} already running", sectionId);
            return;
        }

        try {
            if (!isConfirmed(sectionId)) return; // unconfirmed while queued

            List<TaskBlock> tasks = taskRepository.findByExamIdAndSectionIdOrderByPositionAsc(examId, sectionId);
            if (tasks.isEmpty()) return;

            // Re-confirm case: remove the goals from the previous run so the course doesn't
            // accumulate duplicates.
            removeGoals(detachGoals(tasks.stream().map(TaskBlock::getId).toList()));

            List<ExamGoalDerivation.ExamTask> blocks = buildBlocks(sectionId, tasks);
            List<ExamGoalDerivation.TaskGoals> result = derivation.derive(courseId, blocks);

            List<UUID> fresh = result.stream()
                .flatMap(entry -> entry.goals() == null ? java.util.stream.Stream.<GoalInfo>empty() : entry.goals().stream())
                .map(GoalInfo::id).toList();

            // If the user unconfirmed mid-run the content may change again, and if the exam was
            // moved to another course the goals sit in the wrong one: drop the result and
            // compensate by removing the fresh goals.
            if (!isConfirmed(sectionId)) {
                removeGoals(fresh);
                return;
            }
            if (!inCourse(examId, courseId)) {
                log.info("Exam {} moved to another course during goal generation; dropping the result", examId);
                removeGoals(fresh);
                return;
            }

            Map<String, TaskBlock> byId = new HashMap<>();
            for (TaskBlock t : tasks) byId.put(t.getId().toString(), t);
            int linked = 0;
            for (ExamGoalDerivation.TaskGoals entry : result) {
                TaskBlock t = byId.get(entry.blockId());
                if (t == null || entry.goals() == null) continue;
                for (GoalInfo g : entry.goals()) goalLinks.link(g.id(), t.getId());
                linked++;
            }
            // A move between the check above and the links: the move removed the old course's
            // links before ours were written, so remove ours and the goals now.
            if (!inCourse(examId, courseId)) {
                log.info("Exam {} moved to another course while linking goals; dropping the result", examId);
                goalLinks.unlinkInCourse(tasks.stream().map(TaskBlock::getId).toList(), courseId);
                removeGoals(fresh);
                return;
            }
            log.info("Linked learning goals to {} task(s) in section {}", linked, sectionId);
            sse.tasksUpdated(examId); // SseHub never throws
        } finally {
            try {
                sectionRepository.releaseGoalsLock(sectionId);
            } catch (RuntimeException ignored) {}
        }
    }

    private boolean inCourse(UUID examId, UUID courseId) {
        return examRepository.findById(examId).map(e -> courseId.equals(e.getCourseId())).orElse(false);
    }

    private boolean isConfirmed(UUID sectionId) {
        Section s = sectionRepository.findById(sectionId).orElse(null);
        return s != null && s.getConfirmedAt() != null;
    }

    /**
     * Interleave the section's context blocks and tasks by position — the derivation
     * attaches every context block to the task blocks after it, so order
     * matters. Figure blocks and blank context are skipped.
     */
    private List<ExamGoalDerivation.ExamTask> buildBlocks(UUID sectionId, List<TaskBlock> tasks) {
        record Positioned(int position, ExamGoalDerivation.ExamTask block) {}
        List<Positioned> items = new ArrayList<>();
        for (SectionBlock b : sectionBlockRepository.findBySectionIdOrderByPositionAsc(sectionId)) {
            if (!"context".equals(b.getKind())) continue;
            if (b.getContent() == null || b.getContent().isBlank()) continue;
            items.add(new Positioned(b.getPosition(),
                new ExamGoalDerivation.ExamTask(b.getId().toString(), "context", null, b.getContent())));
        }
        for (TaskBlock t : tasks) {
            items.add(new Positioned(t.getPosition(),
                new ExamGoalDerivation.ExamTask(t.getId().toString(), "task", taskTypeLabel(t.getType()), taskDescription(t))));
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

    /** Prompt plus the option texts, so the model sees the full task. */
    private static String taskDescription(TaskBlock t) {
        StringBuilder sb = new StringBuilder(t.getPrompt() == null ? "" : t.getPrompt());
        if (t.getOptions() != null && !t.getOptions().isEmpty()) {
            sb.append("\n\nOptions:");
            for (AnswerOption o : t.getOptions()) {
                sb.append("\n- ").append(o.text());
            }
        }
        return sb.toString();
    }
}
