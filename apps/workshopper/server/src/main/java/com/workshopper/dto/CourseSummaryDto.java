package com.workshopper.dto;

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
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime createdAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime updatedAt
) {}
