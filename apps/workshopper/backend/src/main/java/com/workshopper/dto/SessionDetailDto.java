package com.workshopper.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Full session detail including the draft state JSON blob for resumption.
 * Returned by GET /api/workshop/sessions/{id}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SessionDetailDto(
        String id,
        String title,
        String status,
        String currentStep,
        String type,
        String lectureId,
        /** Draft state blob (opaque JSON string for the frontend to parse) */
        String draftStateJson,
        /** Only populated when status == "complete" */
        WorkshopSessionDto session
) {}
