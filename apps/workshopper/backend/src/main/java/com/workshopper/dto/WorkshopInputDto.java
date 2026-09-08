package com.workshopper.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkshopInputDto(
        String title,
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
        String uploadedMaterialsText,
        List<EvaluateMappingDto> evaluateMappings
) {
    public WorkshopInputDto(
            String title,
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
    ) {
        this(title, learningGoals, duration, participants, sessionType, sessionTypeOther,
                studentBackground, prerequisites, sourceDocument, interactionLevel,
                selectedActivities, uploadedMaterialsText, null);
    }
}
