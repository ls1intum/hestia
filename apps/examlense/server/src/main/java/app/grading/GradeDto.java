package app.grading;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Response DTO for an answer grade, with task/exam compatibility fields derived from that answer. */
public record GradeDto(
    UUID id, UUID answer_id, UUID task_id, UUID exam_id, BigDecimal score, boolean auto_graded,
    UUID graded_by, OffsetDateTime created_at, OffsetDateTime updated_at
) {
    public static GradeDto from(Grade g) {
        var answer = g.getAnswer();
        return new GradeDto(g.getId(), answer.getId(), answer.getTaskId(), answer.getExamId(),
            g.getScore(), g.isAutoGraded(),
            g.getGradedBy(), g.getCreatedAt(), g.getUpdatedAt());
    }
}
