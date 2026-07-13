package app.parse;

import app.ai.AiProvider;
import java.util.UUID;

/**
 * Mutable per-parse accumulator. {@link ParseExamService#runAsync} creates one
 * at the top of the pipeline and populates fields as it progresses; a
 * try/finally hands it to {@link ParseMetricsRecorder} on every exit path so
 * exactly one {@code parse_metrics} row is written per parse (success or failure).
 */
class ParseAttempt {
    final UUID examId;
    final String ownerId;
    final String parserModel;
    final String pdfMode;

    Integer pageCount;
    Long llmMs;
    AiProvider.Usage usage;
    long durationMs;
    boolean success;
    String error;

    ParseAttempt(UUID examId, String ownerId, String parserModel, String pdfMode) {
        this.examId = examId;
        this.ownerId = ownerId;
        this.parserModel = parserModel;
        this.pdfMode = pdfMode;
    }
}
