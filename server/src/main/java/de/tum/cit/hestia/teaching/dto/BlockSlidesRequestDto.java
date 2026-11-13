package de.tum.cit.hestia.teaching.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BlockSlidesRequestDto(
        ActivityBlockDto block,
        WorkshopInputDto meta,
        List<LearningGoalPlanDto> goals
) {}
