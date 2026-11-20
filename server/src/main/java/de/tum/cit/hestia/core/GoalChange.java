package de.tum.cit.hestia.core;

import java.util.Objects;

/**
 * The new values of a goal's versioned fields; every field replaces the current one (null clears
 * the optional ones). Start from {@link GoalInfo#asChange()} and replace what changes. A change
 * equal to the current version writes no new version.
 */
public record GoalChange(
        String text, String shortLabel, GoalKind kind, GoalRole role, String bloomLevel, String soloLevel) {

    public GoalChange {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(kind, "kind");
    }

    public GoalChange withText(String value) {
        return new GoalChange(value, shortLabel, kind, role, bloomLevel, soloLevel);
    }

    public GoalChange withShortLabel(String value) {
        return new GoalChange(text, value, kind, role, bloomLevel, soloLevel);
    }

    public GoalChange withKind(GoalKind value) {
        return new GoalChange(text, shortLabel, value, role, bloomLevel, soloLevel);
    }

    public GoalChange withRole(GoalRole value) {
        return new GoalChange(text, shortLabel, kind, value, bloomLevel, soloLevel);
    }

    public GoalChange withBloomLevel(String value) {
        return new GoalChange(text, shortLabel, kind, role, value, soloLevel);
    }

    public GoalChange withSoloLevel(String value) {
        return new GoalChange(text, shortLabel, kind, role, bloomLevel, value);
    }
}
