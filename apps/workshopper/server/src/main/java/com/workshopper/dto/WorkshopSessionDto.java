package com.workshopper.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkshopSessionDto(
        String id,
        String title,
        String studentBackground,
        List<ActivityBlockDto> blocks,
        /** Cached slides mapped by block index */
        java.util.Map<Integer, List<java.util.Map<String, Object>>> slides
) {}
