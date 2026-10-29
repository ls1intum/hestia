package com.workshopper.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SubSkillDto(
        String id,
        String text,
        String bloomLevel,
        String soloLevel
) {}
