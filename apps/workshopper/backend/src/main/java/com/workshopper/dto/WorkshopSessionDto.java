package com.workshopper.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkshopSessionDto(
        String id,
        String title,
        String learningGoal,
        String studentBackground,
        String prerequisites,
        List<ActivityBlockDto> blocks,
        /** Goals that were omitted because there was genuinely not enough time to cover them. May be null or empty. */
        List<String> omittedGoals,
        /** Cached slides mapped by block index */
        java.util.Map<Integer, List<java.util.Map<String, Object>>> slides
) {}
