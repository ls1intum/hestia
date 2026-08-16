package app.parse;

import app.exam.ExamRepository;
import app.sse.SseHub;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Progress/failure reporting for the parse pipeline: phase updates and status
 * flips always travel together with their SSE notification. All methods are
 * best-effort — a progress write must never break the parse itself.
 */
@Component
class ParseProgress {

    private static final Logger log = LoggerFactory.getLogger(ParseProgress.class);

    private final ExamRepository examRepository;
    private final SseHub sse;

    ParseProgress(ExamRepository examRepository, SseHub sse) {
        this.examRepository = examRepository;
        this.sse = sse;
    }

    void setPhase(UUID examId, String phase) {
        try {
            examRepository.updateParsePhase(examId, phase);
            sse.examUpdated(examId);
        } catch (Exception e) {
            log.warn("parse-exam-pdf[{}] setPhase({}) failed: {}", examId, phase, e.getMessage());
        }
    }

    void notifyExam(UUID examId) {
        sse.examUpdated(examId);
    }

    /** Record the model that actually parsed (e.g. after a fallback). Best-effort. */
    void setParserModel(UUID examId, String model) {
        try {
            examRepository.updateParserModel(examId, model);
            sse.examUpdated(examId);
        } catch (Exception e) {
            log.warn("parse-exam-pdf[{}] setParserModel({}) failed: {}", examId, model, e.getMessage());
        }
    }

    /** Flip the exam to failed with a user-facing message. */
    void fail(UUID examId, String message) {
        log.error("parse-exam-pdf[{}] failed: {}", examId, message);
        try {
            examRepository.markParseFailed(examId, message);
            sse.examUpdated(examId);
        } catch (Exception e) {
            log.error("parse-exam-pdf[{}] failed to record failure: {}", examId, e.getMessage());
        }
    }
}
