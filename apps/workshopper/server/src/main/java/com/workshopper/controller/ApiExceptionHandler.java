package com.workshopper.controller;

import com.workshopper.exception.SessionLockedException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Map;

/**
 * Turns exceptions thrown by any controller into HTTP answers, so controllers need no try/catch:
 * <ul>
 *   <li>{@link AccessDeniedException} (ownership checks) → 403</li>
 *   <li>{@link SessionLockedException} (a generation for the session is already running) → 409</li>
 *   <li>{@code ResponseStatusException} (e.g. unknown course) and Spring MVC errors such as
 *       unreadable JSON → their own status (handled by {@link ResponseEntityExceptionHandler})</li>
 *   <li>anything else → 500 {@code {"error": "..."}}, logged with the request</li>
 * </ul>
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<String> handleAccessDenied(AccessDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Access Denied");
    }

    @ExceptionHandler(SessionLockedException.class)
    public ResponseEntity<Map<String, String>> handleSessionLocked(SessionLockedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("{} {} failed", request.getMethod(), request.getRequestURI(), ex);
        return ResponseEntity.internalServerError()
                .body(Map.of("error", "The request failed on the server. Please try again."));
    }
}
