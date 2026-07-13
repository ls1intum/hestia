package com.workshopper.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BlockSlidesRequestDto(
        ActivityBlockDto block,
        WorkshopInputDto meta
) {}
