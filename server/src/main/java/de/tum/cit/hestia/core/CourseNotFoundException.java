package de.tum.cit.hestia.core;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The course does not exist or the current user is not a member of it (answered 404). */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class CourseNotFoundException extends RuntimeException {

    public CourseNotFoundException(UUID courseId) {
        super("Course " + courseId + " not found");
    }
}
