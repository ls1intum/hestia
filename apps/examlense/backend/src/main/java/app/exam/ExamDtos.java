package app.exam;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Response DTOs for the exam slice. Component names are snake_case to match the
 * row shape the frontend consumes (e.g. {@code lgh_course_id}); we deliberately
 * avoid a global Jackson snake_case strategy so other camelCase payloads (e.g.
 * {@code /api/parse-metrics}) are unaffected.
 */
public final class ExamDtos {

    private ExamDtos() {}

    public record ExamDto(
        UUID id, UUID owner_id, String title, String course, String semester,
        String instructor_name, BigDecimal total_points, String language, String source,
        String source_file_url, String status, String parse_error, String parse_phase,
        String parser_model, String solver_model, String parse_raw_text,
        Long lgh_course_id, OffsetDateTime created_at, OffsetDateTime updated_at
    ) {
        public static ExamDto from(Exam e) {
            return new ExamDto(e.getId(), e.getOwnerId(), e.getTitle(), e.getCourse(), e.getSemester(),
                e.getInstructorName(), e.getTotalPoints(), e.getLanguage(), e.getSource(),
                e.getSourceFileUrl(), e.getStatus(), e.getParseError(), e.getParsePhase(),
                e.getParserModel(), e.getSolverModel(), e.getParseRawText(),
                e.getLghCourseId(), e.getCreatedAt(), e.getUpdatedAt());
        }
    }

    /**
     * List-only variant of {@link ExamDto} that additionally carries the exam's
     * progress counts for the dashboard table. {@code @JsonUnwrapped} flattens
     * the exam fields so the JSON shape stays a superset of {@code ExamDto}
     * (the frontend reads {@code ExamListItem extends Exam}). The plain
     * {@code ExamDto} is left untouched for single-exam endpoints.
     */
    public record ExamListItemDto(
        @JsonUnwrapped ExamDto exam,
        long task_count, long scored_count, long answered_count, long graded_count
    ) {
        public static ExamListItemDto from(Exam e, ExamProgressService.Counts c) {
            return new ExamListItemDto(ExamDto.from(e),
                c.taskCount(), c.scoredCount(), c.answeredCount(), c.gradedCount());
        }
    }
}
