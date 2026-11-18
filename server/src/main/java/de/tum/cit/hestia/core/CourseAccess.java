package de.tum.cit.hestia.core;

import java.util.UUID;

/**
 * Checks the current user's role in a course ({@code core.course}). The {@code require*} methods
 * throw {@link CourseNotFoundException} (answered 404) without a membership and
 * {@link CourseAccessDeniedException} (answered 403) for an insufficient role. The bean is named
 * {@code courseAccess}, for {@code @PreAuthorize("@courseAccess.canWrite(#courseId)")}.
 */
public interface CourseAccess {

    void requireRead(UUID courseId);

    void requireWrite(UUID courseId);

    void requireOwner(UUID courseId);

    boolean canRead(UUID courseId);

    boolean canWrite(UUID courseId);
}
