package de.tum.cit.hestia.core;

import java.time.Instant;
import java.util.UUID;

/**
 * An alignment link marked for review, because its goal changed ({@code GOAL_CHANGED}) or was
 * removed ({@code GOAL_REMOVED}) after the link was made or last confirmed. {@code seenText} is the
 * goal's text in {@code goalVersionSeen}, {@code currentText} in {@code goalVersion}.
 * {@code targetLabel} names the linked object as its module shows it, or is null if the module
 * does not name it.
 */
public record AlignmentReview(
        UUID linkId, UUID courseId, UUID goalId, DomainModule module, String targetType, String targetId,
        String targetLabel, String reason, int goalVersionSeen, int goalVersion, String seenText,
        String currentText, boolean goalRemoved, Instant linkedAt) {}
