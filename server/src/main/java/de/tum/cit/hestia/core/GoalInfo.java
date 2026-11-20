package de.tum.cit.hestia.core;

import java.time.Instant;
import java.util.UUID;

/**
 * A learning goal in its current version. {@code originalText} is the text of version 1 (the
 * generated or first wording); {@code removedAt} is set once the goal was removed, which only
 * {@link LearningGoalApi#goal(UUID)} and {@link LearningGoalApi#goalsById} still return.
 * Bloom and SOLO levels are the names of the materials taxonomy values.
 */
public record GoalInfo(
        UUID id,
        UUID courseId,
        int version,
        String text,
        String originalText,
        String shortLabel,
        GoalKind kind,
        GoalRole role,
        String bloomLevel,
        String soloLevel,
        GoalStatus status,
        GoalOrigin origin,
        String creationProvenance,
        DomainModule createdVia,
        UUID createdBy,
        Instant createdAt,
        Instant removedAt) {

    public boolean removed() {
        return removedAt != null;
    }

    /** The versioned fields of this goal, to change some of them with {@link LearningGoalApi#update}. */
    public GoalChange asChange() {
        return new GoalChange(text, shortLabel, kind, role, bloomLevel, soloLevel);
    }
}
