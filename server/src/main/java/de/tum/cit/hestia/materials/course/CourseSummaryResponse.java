package de.tum.cit.hestia.materials.course;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CourseSummaryResponse(
        UUID id, String name, String outputLanguage, boolean figuresEnabled, OffsetDateTime createdAt, boolean personal,
        long documentCount, long goalCount, long topicCount, long skillCount,
        OffsetDateTime skillsReviewedAt,
        ExtractionStatus extractionStatus) {
}
