package de.tum.cit.hestia.teaching.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EvaluateMappingDto(
    String method,
    List<String> lgIds
) {}
