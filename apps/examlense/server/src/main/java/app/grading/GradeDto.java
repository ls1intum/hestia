package app.grading;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Response DTO for a task grade (auto or manual). */
public record GradeDto(
    UUID id, UUID task_id, UUID exam_id, BigDecimal score, boolean auto_graded,
    UUID graded_by, OffsetDateTime created_at, OffsetDateTime updated_at
) {
    public static GradeDto from(Grade g) {
        return new GradeDto(g.getId(), g.getTaskId(), g.getExamId(), g.getScore(), g.isAutoGraded(),
            g.getGradedBy(), g.getCreatedAt(), g.getUpdatedAt());
    }
}
