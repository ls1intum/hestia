package com.workshopper.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * Request for assembling a PPTX from pre-built slides (cache bypass — no LLM needed).
 * If prebuiltSlides is non-null, LLM generation is skipped entirely.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PptxAssembleRequestDto(
        WorkshopSessionDto session,
        WorkshopInputDto meta,
        List<Map<String, Object>> prebuiltSlides
) {}
