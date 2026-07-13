package com.workshopper.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Phase A LLM output: lightweight session skeleton.
 * Only contains the sequence of phases, which LG each covers, and duration.
 * All rich content (methods, materials, descriptions) is added in Phase B by the backend.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SessionSkeletonDto(
        /** One-sentence summary of the overall learning goal for the session. */
        String learningGoal,
        /** Ordered list of blocks in the session. */
        List<SkeletonBlockDto> blocks,
        /**
         * 1-based indices of LGs that could not be covered (empty if all covered).
         * e.g. [3] means LG3 was omitted due to time constraints.
         */
        List<Integer> omittedGoalIndices,
        /**
         * Optional: the existing draft session ID to update on finalisation.
         * If null, a new session record is created.
         */
        String sessionId
) {}
