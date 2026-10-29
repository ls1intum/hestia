package com.workshopper.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkshopSessionDto(
        String id,
        String title,
        String studentBackground,
        List<ActivityBlockDto> blocks,
        /** Cached slides mapped by block index */
        Map<Integer, List<Map<String, Object>>> slides
) {}
