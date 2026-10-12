package com.workshopper.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ActivitySectionDto(
        String title,
        int duration,
        List<String> steps,
        List<String> methods,
        List<String> materials
) {}
