package de.tum.cit.hestia.teaching.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDateTime;

/** Lightweight summary of a Course for the dashboard list view. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CourseSummaryDto(
        String id,
        String title,
        String status,
        String currentStep,
        /** The user's personal catch-all course ("Einzelkram"); it cannot be deleted */
        boolean personal,
        /** The user's role in the course: VIEWER, EDITOR or OWNER */
        String role,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime createdAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime updatedAt
) {}
