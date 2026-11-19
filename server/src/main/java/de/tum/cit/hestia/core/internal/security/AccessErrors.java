package de.tum.cit.hestia.core.internal.security;

import de.tum.cit.hestia.core.CourseAccessDeniedException;
import de.tum.cit.hestia.core.CourseNotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The same answers for access errors in every module, ahead of the modules' own exception
 * handlers (which turn unknown exceptions into 500): no membership 404, too small a role or a
 * refused method-security check 403, not logged in 401. The body carries both error shapes the
 * clients already read: {@code code}/{@code message} (LearningGoalHub) and {@code error} (ExamLense).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class AccessErrors {

    @ExceptionHandler(CourseNotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(CourseNotFoundException e) {
        return body(HttpStatus.NOT_FOUND, "Not found");
    }

    @ExceptionHandler({CourseAccessDeniedException.class, AccessDeniedException.class})
    ResponseEntity<Map<String, String>> forbidden(RuntimeException e) {
        return body(HttpStatus.FORBIDDEN, "Forbidden");
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Map<String, String>> unauthorized(AuthenticationException e) {
        return body(HttpStatus.UNAUTHORIZED, "Unauthorized");
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("code", status.name(), "message", message, "error", message));
    }
}
