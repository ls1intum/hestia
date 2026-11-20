package de.tum.cit.hestia.core.events;

import java.util.UUID;

/** A goal was removed (softly); its alignment links stay for the review list. */
public record GoalRemoved(UUID orgId, UUID courseId, UUID goalId, int lastVersion) {}
