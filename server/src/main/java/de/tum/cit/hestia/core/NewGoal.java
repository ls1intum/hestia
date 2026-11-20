package de.tum.cit.hestia.core;

import java.util.Objects;

/** A goal to create; text, kind, status, origin and createdVia are required. */
public record NewGoal(
        String text,
        String shortLabel,
        GoalKind kind,
        GoalRole role,
        String bloomLevel,
        String soloLevel,
        GoalStatus status,
        GoalOrigin origin,
        String creationProvenance,
        DomainModule createdVia) {

    public NewGoal {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(createdVia, "createdVia");
    }

    /** A pending explicit goal with only a text, as most callers create them. */
    public static NewGoal of(String text, GoalOrigin origin, DomainModule createdVia) {
        return new NewGoal(text, null, GoalKind.EXPLICIT, null, null, null, GoalStatus.PENDING, origin, null, createdVia);
    }
}
