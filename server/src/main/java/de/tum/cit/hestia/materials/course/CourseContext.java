package de.tum.cit.hestia.materials.course;

import java.util.UUID;

/** A {@code core.course} the user can read, with the materials settings processing uses. */
public record CourseContext(UUID id, String name, String outputLanguage, boolean figuresEnabled) {
}
