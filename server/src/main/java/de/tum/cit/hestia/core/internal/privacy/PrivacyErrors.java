package de.tum.cit.hestia.core.internal.privacy;

import de.tum.cit.hestia.core.UploadConfirmationOutdatedException;
import de.tum.cit.hestia.core.UploadConfirmationRequiredException;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The same answers in every module, ahead of the modules' own exception handlers: an upload without
 * confirmation is 428 {@code UPLOAD_CONFIRMATION_REQUIRED}, an outdated confirmation 409. The body
 * carries both error shapes the clients read ({@code code}/{@code message} and {@code error}).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class PrivacyErrors {

    @ExceptionHandler(UploadConfirmationRequiredException.class)
    ResponseEntity<Map<String, String>> required(UploadConfirmationRequiredException e) {
        return ResponseEntity.status(HttpStatus.PRECONDITION_REQUIRED).body(Map.of(
                "code", UploadConfirmationRequiredException.CODE, "message", e.getMessage(), "error", e.getMessage()));
    }

    @ExceptionHandler(UploadConfirmationOutdatedException.class)
    ResponseEntity<Map<String, String>> outdated(UploadConfirmationOutdatedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "code", "UPLOAD_CONFIRMATION_OUTDATED", "message", e.getMessage(), "error", e.getMessage()));
    }
}
