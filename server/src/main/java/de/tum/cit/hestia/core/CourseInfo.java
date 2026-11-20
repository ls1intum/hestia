package de.tum.cit.hestia.core;

import java.time.Instant;
import java.util.UUID;

/**
 * A course of {@code core} as seen by the current user, with that user's role in it.
 * {@code personal} marks the user's catch-all course (shown by the clients as "Einzelkram").
 */
public record CourseInfo(UUID id, String title, CourseRole role, Instant createdAt, boolean personal) {}
