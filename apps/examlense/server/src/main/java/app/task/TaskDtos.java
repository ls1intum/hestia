package app.task;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Response DTOs for the task slice (tasks and their AI-generated answers). */
public final class TaskDtos {

    private TaskDtos() {}

    public record TaskDto(
        UUID id, UUID exam_id, UUID section_id, int position, String section, String type,
        String prompt, List<TaskOption> options, String reference_answer, BigDecimal points,
        String parse_confidence, List<Long> learning_goal_ids,
        OffsetDateTime created_at, OffsetDateTime updated_at
    ) {
        public static TaskDto from(Task t) {
            return new TaskDto(t.getId(), t.getExamId(), t.getSectionId(), t.getPosition(),
                t.getSection(), t.getType(), t.getPrompt(), t.getOptions(), t.getReferenceAnswer(),
                t.getPoints(), t.getParseConfidence(), t.getLearningGoalIds(),
                t.getCreatedAt(), t.getUpdatedAt());
        }
    }

    public record AnswerDto(
        UUID id, UUID task_id, UUID exam_id, List<UUID> selected_option_ids,
        String answer_text, String reasoning, String provider, String model, OffsetDateTime created_at
    ) {
        public static AnswerDto from(TaskAnswer a) {
            return new AnswerDto(a.getId(), a.getTaskId(), a.getExamId(), a.getSelectedOptionIds(),
                a.getAnswerText(), a.getReasoning(), a.getProvider(), a.getModel(), a.getCreatedAt());
        }
    }
}
