package com.workshopper.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * A single block in the Phase A skeleton.
 * lgIndex is 1-based (1 = LG1, 2 = LG2, …).
 * lgIndex = 0 means this block does not target a specific LG
 * (used for ARRIVE, ACTIVATE, BREAK, SUMMARY).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SkeletonBlockDto(
        String phase,
        int lgIndex,
        int duration,
        String title,
        String description,
        List<SkeletonSectionDto> sections
) {}
