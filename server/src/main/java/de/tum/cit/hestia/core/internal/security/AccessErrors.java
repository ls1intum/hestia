package de.tum.cit.hestia.core.internal.security;

import de.tum.cit.hestia.core.CourseAccessDeniedException;
import de.tum.cit.hestia.core.CourseNotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The same answers for access errors in every module, ahead of the modules' own exception
 * handlers (which turn unknown exceptions into 500): no membership 404, too small a role or a
 * refused method-security check 403, not logged in 401.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class AccessErrors {

    @ExceptionHandler(CourseNotFoundException.class)
    ProblemDetail notFound(CourseNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Not found");
    }

    @ExceptionHandler({CourseAccessDeniedException.class, AccessDeniedException.class})
    ProblemDetail forbidden(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Forbidden");
    }

    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail unauthorized(AuthenticationException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Unauthorized");
    }
}
