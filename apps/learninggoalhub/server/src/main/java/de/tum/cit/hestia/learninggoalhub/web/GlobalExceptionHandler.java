package de.tum.cit.hestia.learninggoalhub.web;

import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Translates exceptions into a stable {@code {code, message}} JSON body, so API consumers get a
 * machine-readable error code alongside a human-readable message (per the proposal's error-format
 * requirement). Extends {@link ResponseEntityExceptionHandler} so Spring MVC's own exceptions keep
 * their correct status (400 for bad input, 404 for unknown paths, …) instead of collapsing into 500.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        return new ResponseEntity<>(new ApiError(codeFor(statusCode), messageFor(ex, statusCode)), headers, statusCode);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        // Anything the framework did not already map is a bug or an unexpected failure: log it and
        // return a generic 500 without leaking internal exception details to the consumer.
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError(HttpStatus.INTERNAL_SERVER_ERROR.name(), "Unexpected server error"));
    }

    private static String codeFor(HttpStatusCode statusCode) {
        HttpStatus resolved = HttpStatus.resolve(statusCode.value());
        return resolved != null ? resolved.name() : "HTTP_" + statusCode.value();
    }

    private static String messageFor(Exception ex, HttpStatusCode statusCode) {
        if (ex instanceof ResponseStatusException rse && rse.getReason() != null) {
            return rse.getReason();
        }
        if (ex instanceof MethodArgumentNotValidException manv) {
            String fields = manv.getBindingResult().getFieldErrors().stream()
                    .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                    .collect(Collectors.joining("; "));
            if (!fields.isEmpty()) {
                return fields;
            }
        }
        HttpStatus resolved = HttpStatus.resolve(statusCode.value());
        return resolved != null ? resolved.getReasonPhrase() : "Request failed";
    }

    /** Stable error body: a machine-readable {@code code} (the HTTP status name) and a {@code message}. */
    public record ApiError(String code, String message) {
    }
}
