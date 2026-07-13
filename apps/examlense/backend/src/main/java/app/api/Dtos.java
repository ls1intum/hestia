package app.api;

import app.persistence.entity.Exam;
import app.persistence.entity.Section;
import app.persistence.entity.SectionBlock;
import app.persistence.entity.SectionFigure;
import app.persistence.entity.Task;
import app.persistence.entity.TaskAnswer;
import app.persistence.entity.ParseSurvey;
import app.persistence.entity.TaskGrade;
import app.persistence.entity.TaskOption;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Response DTOs returned to the frontend. Component names are snake_case to
 * match the row shape the frontend already consumes from Supabase (e.g.
 * {@code exam_id}, {@code learning_goal_ids}), so the Phase 3 cutover is a near
 * drop-in. (We deliberately avoid a global Jackson snake_case strategy, which
 * would also rename the existing camelCase {@code /api/parse-metrics} payload.)
 */
public final class Dtos {

    private Dtos() {}

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

    public record SectionDto(
        UUID id, UUID exam_id, int position, String name,
        OffsetDateTime confirmed_at, OffsetDateTime solve_started_at,
        OffsetDateTime created_at, OffsetDateTime updated_at
    ) {
        public static SectionDto from(Section s) {
            return new SectionDto(s.getId(), s.getExamId(), s.getPosition(), s.getName(),
                s.getConfirmedAt(), s.getSolveStartedAt(), s.getCreatedAt(), s.getUpdatedAt());
        }
    }

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

    public record BlockDto(
        UUID id, UUID exam_id, UUID section_id, int position, String content, String kind,
        OffsetDateTime created_at, OffsetDateTime updated_at
    ) {
        public static BlockDto from(SectionBlock b) {
            return new BlockDto(b.getId(), b.getExamId(), b.getSectionId(), b.getPosition(),
                b.getContent(), b.getKind(), b.getCreatedAt(), b.getUpdatedAt());
        }
    }

    public record FigureDto(
        UUID id, UUID block_id, String storage_path, String caption, int position,
        String source, OffsetDateTime created_at
    ) {
        public static FigureDto from(SectionFigure f) {
            return new FigureDto(f.getId(), f.getBlockId(), f.getStoragePath(), f.getCaption(),
                f.getPosition(), f.getSource(), f.getCreatedAt());
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

    public record SurveyDto(
        UUID id, UUID exam_id, Short speed, Short content_correctness, Short structure, OffsetDateTime created_at
    ) {
        public static SurveyDto from(ParseSurvey s) {
            return new SurveyDto(s.getId(), s.getExamId(), s.getSpeed(), s.getContentCorrectness(),
                s.getStructure(), s.getCreatedAt());
        }
    }

    /**
     * Per-model rollup of parsing-quality survey scores (see
     * {@link app.persistence.repository.ParseSurveyRepository#aggregateByModel()}).
     * Averages are null when no response for that model rated the aspect.
     */
    public record SurveyModelDto(
        String model_id, long responses, Double avg_speed,
        Double avg_content_correctness, Double avg_structure
    ) {
        public static SurveyModelDto from(Object[] row) {
            return new SurveyModelDto(
                (String) row[0],
                ((Number) row[1]).longValue(),
                row[2] == null ? null : ((Number) row[2]).doubleValue(),
                row[3] == null ? null : ((Number) row[3]).doubleValue(),
                row[4] == null ? null : ((Number) row[4]).doubleValue()
            );
        }
    }

    public record GradeDto(
        UUID id, UUID task_id, UUID exam_id, BigDecimal score, boolean auto_graded,
        String feedback, UUID graded_by, OffsetDateTime created_at, OffsetDateTime updated_at
    ) {
        public static GradeDto from(TaskGrade g) {
            return new GradeDto(g.getId(), g.getTaskId(), g.getExamId(), g.getScore(), g.isAutoGraded(),
                g.getFeedback(), g.getGradedBy(), g.getCreatedAt(), g.getUpdatedAt());
        }
    }
}
