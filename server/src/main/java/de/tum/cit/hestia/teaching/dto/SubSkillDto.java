package de.tum.cit.hestia.teaching.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SubSkillDto(
        String id,
        String text,
        String bloomLevel,
        String soloLevel
) {}
