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
        /** Whether a custom slide template applies (the session's own or its course's) */
        boolean hasTemplate,
        /** Where the template comes from: "SESSION", "COURSE", or null for the default design */
        String templateSource,
        /** File name of the applied template, if known */
        String templateName,
        /** Only populated when status == "complete" */
        WorkshopSessionDto session
) {}
