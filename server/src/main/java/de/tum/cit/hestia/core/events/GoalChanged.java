package de.tum.cit.hestia.core.events;

import java.util.UUID;

/** A goal got a new version (its text, label, kind, role or levels changed). */
public record GoalChanged(UUID orgId, UUID courseId, UUID goalId, int fromVersion, int toVersion) {}
