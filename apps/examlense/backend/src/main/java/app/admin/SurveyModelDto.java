package app.admin;

/**
 * Per-model rollup of parsing-quality survey scores (see
 * {@code app.parse.ParseSurveyRepository#aggregateByModel()}). Averages are null
 * when no response for that model rated the aspect.
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
