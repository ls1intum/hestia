package app.parse;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Response DTO for a single parsing-quality survey response. */
public record SurveyDto(
    UUID id, UUID exam_id, Short speed, Short content_correctness, Short structure, OffsetDateTime created_at
) {
    public static SurveyDto from(ParseSurvey s) {
        return new SurveyDto(s.getId(), s.getExamId(), s.getSpeed(), s.getContentCorrectness(),
            s.getStructure(), s.getCreatedAt());
    }
}
