package com.workshopper.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDateTime;

/**
 * Lightweight summary used on the sessions dashboard (list view).
 * Does NOT include the full blocks / session JSON to keep the response lean.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SessionSummaryDto(
        String id,
        String title,
        String learningGoal,
        String status,
        String currentStep,
        String type,
        String lectureId,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime createdAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime updatedAt
) {}
