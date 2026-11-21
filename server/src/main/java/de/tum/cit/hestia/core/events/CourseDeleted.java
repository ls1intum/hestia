package de.tum.cit.hestia.core.events;

import java.util.UUID;

/**
 * A course is being deleted; published inside the deleting transaction, after the
 * {@code core.spi.CourseDeletion} hooks ran. Rows of the course go by cascade.
 */
public record CourseDeleted(UUID orgId, UUID courseId) {}
