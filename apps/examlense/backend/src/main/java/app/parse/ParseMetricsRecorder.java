package app.parse;

import app.persistence.entity.ParseMetric;
import app.persistence.repository.ExamRepository;
import app.persistence.repository.ParseMetricRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Writes one {@code parse_metrics} row per parse attempt. Guarded by a
 * try/catch + warn-log so a metrics failure can never break parsing.
 */
@Service
public class ParseMetricsRecorder {

    private static final Logger log = LoggerFactory.getLogger(ParseMetricsRecorder.class);

    private final ParseMetricRepository repository;
    private final ExamRepository examRepository;

    public ParseMetricsRecorder(ParseMetricRepository repository, ExamRepository examRepository) {
        this.repository = repository;
        this.examRepository = examRepository;
    }

    public void record(ParseAttempt a) {
        try {
            // A long parse can finish after its exam was deleted/cancelled (the
            // persist step already no-ops in that case). Skip the metrics row so
            // we don't hit the parse_metrics → exams FK constraint.
            if (a.examId != null && !examRepository.existsById(a.examId)) {
                log.info("parse-exam-pdf[{}] exam gone before metrics write — skipping", a.examId);
                return;
            }
            ParseMetric m = new ParseMetric();
            m.setExamId(a.examId);
            m.setOwnerId(parseUuidOrNull(a.ownerId));
            m.setParserModel(a.parserModel);
            m.setPdfMode(a.pdfMode);
            m.setPageCount(a.pageCount);
            m.setDurationMs((int) a.durationMs);
            m.setLlmMs(a.llmMs == null ? null : a.llmMs.intValue());
            if (a.usage != null) {
                m.setPromptTokens(a.usage.promptTokens());
                m.setCompletionTokens(a.usage.completionTokens());
                m.setTotalTokens(a.usage.totalTokens());
            }
            m.setSuccess(a.success);
            m.setError(a.error);
            repository.save(m);
        } catch (Exception e) {
            log.warn("parse-exam-pdf[{}] failed to record parse metrics: {}", a.examId, e.getMessage());
        }
    }

    private static UUID parseUuidOrNull(String s) {
        if (s == null) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
