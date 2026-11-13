package de.tum.cit.hestia.teaching.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PdfExportRequestDto(
        WorkshopSessionDto session,
        WorkshopInputDto meta,
        List<LearningGoalPlanDto> goals
) {}
