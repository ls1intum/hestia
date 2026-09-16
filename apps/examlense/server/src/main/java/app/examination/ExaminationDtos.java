package app.examination;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Response DTOs for the exam slice. Component names are snake_case to match the
 * row shape the frontend consumes (e.g. {@code lgh_course_id}); we deliberately
 * avoid a global Jackson snake_case strategy so other camelCase payloads (e.g.
 * {@code /api/parse-metrics}) are unaffected.
 */
public final class ExaminationDtos {

    private ExaminationDtos() {}

    public record ExaminationDto(
        UUID id, UUID owner_id, String title, String course,
        String source,
        String source_file_url, String status, String parse_error, String parse_phase,
        String parser_model, String solver_model,
        Long lgh_course_id, Integer page_count, OffsetDateTime parse_started_at,
        OffsetDateTime parsed_at,
        OffsetDateTime created_at, OffsetDateTime updated_at
    ) {
        public static ExaminationDto from(Examination e) {
            return new ExaminationDto(e.getId(), e.getOwnerId(), e.getTitle(), e.getCourse(),
                e.getSource(),
                e.getSourceFileUrl(), e.getStatus(), e.getParseError(), e.getParsePhase(),
                e.getParserModel(), e.getSolverModel(),
                e.getLghCourseId(), e.getPageCount(), e.getParseStartedAt(),
                e.getParsedAt(),
                e.getCreatedAt(), e.getUpdatedAt());
        }
    }

    /**
     * List-only variant of {@link ExaminationDto} that additionally carries the exam's
     * progress counts for the dashboard table. {@code @JsonUnwrapped} flattens
     * the exam fields so the JSON shape stays a superset of {@code ExaminationDto}
     * (the frontend reads {@code ExaminationListItem extends Examination}). The plain
     * {@code ExaminationDto} is left untouched for single-exam endpoints.
     */
    public record ExaminationListItemDto(
        @JsonUnwrapped ExaminationDto exam,
        long task_count, long scored_count, long answered_count, long graded_count,
        long section_count, long confirmed_section_count
    ) {
        public static ExaminationListItemDto from(Examination e, ExaminationProgressService.Counts c) {
            return new ExaminationListItemDto(ExaminationDto.from(e),
                c.taskCount(), c.scoredCount(), c.answeredCount(), c.gradedCount(),
                c.sectionCount(), c.confirmedSectionCount());
        }
    }
}
