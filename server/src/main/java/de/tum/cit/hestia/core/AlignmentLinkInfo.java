package de.tum.cit.hestia.core;

import java.time.Instant;
import java.util.UUID;

/**
 * A link between a learning goal and an object of a domain module ({@code targetType} e.g.
 * {@code DOCUMENT}, {@code SESSION}, {@code TASK}; {@code targetId} the module's id of it).
 * {@code courseId} is the goal's course. {@code reviewState} is {@code OK} or {@code REVIEW}.
 */
public record AlignmentLinkInfo(
        UUID id, UUID courseId, UUID goalId, DomainModule module, String targetType, String targetId,
        int goalVersionSeen, String reviewState, String reviewReason, Instant createdAt) {}
