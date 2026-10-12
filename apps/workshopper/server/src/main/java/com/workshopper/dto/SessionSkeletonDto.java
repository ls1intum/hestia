package com.workshopper.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * The session outline built by the client (generateDefaultSkeleton): the sequence of phases,
 * which LG each covers, and their durations. The server fills in each block's content.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SessionSkeletonDto(
        /** Ordered list of blocks in the session. */
        List<SkeletonBlockDto> blocks,
        /**
         * Optional: the existing draft session ID to update on finalisation.
         * If null, a new session record is created.
         */
        String sessionId
) {}
