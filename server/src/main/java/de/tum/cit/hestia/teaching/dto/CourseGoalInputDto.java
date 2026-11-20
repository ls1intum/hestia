package de.tum.cit.hestia.teaching.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A goal written or edited in Workshopper: with an id it updates that core goal, without one it creates it. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CourseGoalInputDto(String id, String text) {}
