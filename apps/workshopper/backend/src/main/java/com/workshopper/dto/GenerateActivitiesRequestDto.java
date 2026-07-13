package com.workshopper.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GenerateActivitiesRequestDto(
        List<LearningGoalPlanDto> goals,
        WorkshopInputDto meta,
        String availableMaterials
) {}
