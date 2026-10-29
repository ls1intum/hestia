package de.tum.cit.hestia.learninggoalhub.course;

import java.time.OffsetDateTime;

public record CourseSummaryResponse(
        Long id, String name, String outputLanguage, boolean figuresEnabled, OffsetDateTime createdAt,
        long documentCount, long goalCount, long topicCount, long skillCount,
        OffsetDateTime skillsReviewedAt,
        ExtractionStatus extractionStatus) {
}
