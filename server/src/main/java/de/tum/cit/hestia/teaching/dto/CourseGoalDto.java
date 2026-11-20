package de.tum.cit.hestia.teaching.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A learning goal of a course (core), as the goal picker and the goal step see it. Ids are the
 * core goal ids (UUIDs as strings).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CourseGoalDto(
        String id,
        String text,
        String originalText,
        String bloomLevel,
        String soloLevel,
        String status
) {}
