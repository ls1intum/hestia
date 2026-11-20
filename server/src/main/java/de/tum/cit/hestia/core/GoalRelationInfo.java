package de.tum.cit.hestia.core;

import java.time.Instant;
import java.util.UUID;

/**
 * A directed relationship between two goals of a course. {@code type} and {@code origin} are the
 * names of the materials module's relationship type and origin.
 */
public record GoalRelationInfo(
        UUID id, UUID sourceGoalId, UUID targetGoalId, String type, double confidence, String origin,
        Instant createdAt) {}
