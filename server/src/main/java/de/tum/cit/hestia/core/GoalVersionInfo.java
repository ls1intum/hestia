package de.tum.cit.hestia.core;

import java.time.Instant;
import java.util.UUID;

/** One version of a goal's wording. */
public record GoalVersionInfo(
        UUID goalId, int version, String text, String shortLabel, GoalKind kind, GoalRole role,
        String bloomLevel, String soloLevel, UUID changedBy, Instant createdAt) {}
