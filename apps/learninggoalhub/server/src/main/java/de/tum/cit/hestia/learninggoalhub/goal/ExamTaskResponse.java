package de.tum.cit.hestia.learninggoalhub.goal;

import de.tum.cit.hestia.learninggoalhub.exam.SubmittedExamBlock;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/**
 * The exam task an exam goal was derived from. {@code submittedAt} tells submissions apart: the
 * consumer sends no exam name, so each request to the exam-goal endpoint is its own exam.
 */
public record ExamTaskResponse(Long submissionId, OffsetDateTime submittedAt, Integer taskNumber,
                               @Schema(nullable = true) String taskType,
                               String text,
                               @Schema(nullable = true) String context) {
    static ExamTaskResponse from(SubmittedExamBlock block) {
        return new ExamTaskResponse(block.getSubmission().getId(), block.getSubmission().getCreatedAt(),
                block.getTaskNumber(), block.getTaskType(), block.getDescription(), block.getContext());
    }
}
