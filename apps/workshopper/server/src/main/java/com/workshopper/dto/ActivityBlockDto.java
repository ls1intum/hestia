package com.workshopper.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ActivityBlockDto(
        String blockId,
        String phase,
        String phaseLabel,
        String goalTag,
        String objective,
        String description,
        List<String> methods,
        List<String> materials,
        List<ActivitySectionDto> sections,
        int duration
) {}
