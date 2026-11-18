package de.tum.cit.hestia.core;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The current user is a member of the course, but the role does not allow the action (answered 403). */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class CourseAccessDeniedException extends RuntimeException {

    public CourseAccessDeniedException(UUID courseId, CourseRole needed) {
        super("Course " + courseId + " needs role " + needed);
    }
}
