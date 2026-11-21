package de.tum.cit.hestia.assessment.taskblock;

import de.tum.cit.hestia.assessment.lgh.TaskGoalLinks.TaskGoal;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Response DTOs for the task slice (tasks and their AI-generated answers). */
public final class TaskBlockDtos {

    private TaskBlockDtos() {}

    public record TaskBlockDto(
        UUID id, UUID exam_id, UUID section_id, int position, String section, String type,
        String prompt, List<AnswerOption> options, String reference_answer, BigDecimal points,
        String parse_confidence, List<String> learning_goal_ids, List<LearningGoalDto> learning_goals,
        OffsetDateTime created_at, OffsetDateTime updated_at
    ) {
        public static TaskBlockDto from(TaskBlock t) {
            return from(t, List.of());
        }

        /** The task with its goals as they currently read in {@code core}. */
        public static TaskBlockDto from(TaskBlock t, List<TaskGoal> goals) {
            List<LearningGoalDto> dtos = goals.stream().map(LearningGoalDto::from).toList();
            return new TaskBlockDto(t.getId(), t.getExamId(), t.getSectionId(), t.getPosition(),
                t.getSection(), t.getType(), t.getPrompt(), t.getOptions(), t.getReferenceAnswer(),
                t.getPoints(), t.getParseConfidence(),
                dtos.stream().map(LearningGoalDto::id).toList(), dtos,
                t.getCreatedAt(), t.getUpdatedAt());
        }
    }

    /**
     * A learning goal of a task; the id is the {@code core} goal id as a string. {@code review}
     * ({@code GOAL_CHANGED} / {@code GOAL_REMOVED}) is set while the link waits for the teacher's
     * check, which {@code review_link_id} confirms in core for {@code goal_version}; {@code removed} goals only appear then.
     */
    public record LearningGoalDto(
        String id, String text, String bloom_level, String solo_level, String status,
        String course_id, int goal_version, String review, String review_link_id, boolean removed
    ) {
        public static LearningGoalDto from(TaskGoal tg) {
            var g = tg.goal();
            return new LearningGoalDto(g.id().toString(), g.text(), g.bloomLevel(), g.soloLevel(),
                g.status() == null ? null : g.status().name(), g.courseId().toString(),
                g.version(), tg.review(), tg.linkId() == null ? null : tg.linkId().toString(), tg.removed());
        }
    }

    public record AnswerDto(
        UUID id, UUID task_id, UUID exam_id, List<UUID> selected_option_ids,
        String answer_text, String reasoning, String provider, String model, OffsetDateTime created_at
    ) {
        public static AnswerDto from(AIAnswer a) {
            return new AnswerDto(a.getId(), a.getTaskId(), a.getExamId(), a.getSelectedOptionIds(),
                a.getAnswerText(), a.getReasoning(), a.getProvider(), a.getModel(), a.getCreatedAt());
        }
    }
}
