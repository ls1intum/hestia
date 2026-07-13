package com.workshopper.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * Request body for POST /api/workshop/extract-goals.
 * documentText: plain-text content extracted from the uploaded file.
 * context:      optional session metadata (sessionType, duration, etc.)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExtractGoalsRequestDto(
        String documentText,
        Map<String, Object> context
) {}
