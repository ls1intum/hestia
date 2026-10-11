package com.workshopper.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SkeletonSectionDto(
        String title,
        int duration
) {}
