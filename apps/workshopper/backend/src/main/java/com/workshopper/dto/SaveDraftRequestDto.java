package com.workshopper.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Request body for POST /api/workshop/sessions/draft
 * Saves or updates the in-progress draft state for a session.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SaveDraftRequestDto(
        /** If present, update the existing session with this ID; otherwise create a new one. */
        String sessionId,

        /** The step the user is currently on (e.g. "input-1", "goals", "skeleton") */
        String currentStep,

        /** Human-readable display title derived from step 1 input */
        String title,

        /** First learning goal text, used as subtitle on dashboard */
        String learningGoal,

        /**
         * Full draft state as a JSON string (opaque blob from frontend).
         * Contains: workshopInput, refinedGoals, skeleton, etc. for each step.
         */
        String draftStateJson,

        /** "SESSION" or "LECTURE" */
        String type,

        /** ID of parent lecture, if any */
        String lectureId
) {}
