package de.tum.cit.hestia.teaching.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SkeletonSectionDto(
        String title,
        int duration
) {}
