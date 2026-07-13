package com.workshopper.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LearningGoalPlanDto(
        String id,
        String originalGoal,
        String goal,
        List<String> prerequisites,
        List<String> achieveActivities,
        List<String> assessActivities,
        int priority
) {}
