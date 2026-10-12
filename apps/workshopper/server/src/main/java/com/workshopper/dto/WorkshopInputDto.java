package com.workshopper.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkshopInputDto(
        String title,
        int duration,
        int participants,
        String sessionType,
        String sessionTypeOther,
        String studentBackground,
        List<String> selectedActivities,
        List<EvaluateMappingDto> evaluateMappings
) {
    /** Settings without an evaluate mapping yet. */
    public WorkshopInputDto(
            String title,
            int duration,
            int participants,
            String sessionType,
            String sessionTypeOther,
            String studentBackground,
            List<String> selectedActivities
    ) {
        this(title, duration, participants, sessionType, sessionTypeOther, studentBackground, selectedActivities, null);
    }
}
