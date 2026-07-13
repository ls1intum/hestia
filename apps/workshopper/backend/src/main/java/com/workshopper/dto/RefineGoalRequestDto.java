package com.workshopper.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * Request body for POST /api/workshop/refine-goal.
 * context holds optional metadata (sessionType, duration, participants, studentBackground).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RefineGoalRequestDto(
        String goal,
        Map<String, Object> context
) {}
