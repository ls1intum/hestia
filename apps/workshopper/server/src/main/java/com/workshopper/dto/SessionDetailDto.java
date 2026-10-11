package com.workshopper.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

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
        String courseId,
        /** Draft state blob (opaque JSON string for the client to parse) */
        String draftStateJson,
        /** Indicates if a custom slide template has been uploaded and saved */
        boolean hasTemplate,
        /** Only populated when status == "complete" */
        WorkshopSessionDto session
) {}
