package com.workshopper.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkshopInputDto(
        List<String> learningGoals,
        int duration,
        int participants,
        String sessionType,
        String sessionTypeOther,
        String studentBackground,
        String prerequisites,
        String sourceDocument,
        String interactionLevel,
        List<String> selectedActivities,
        String uploadedMaterialsText
) {}
