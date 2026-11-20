package de.tum.cit.hestia.assessment.error;

import de.tum.cit.hestia.ai.AiExceptions;
import de.tum.cit.hestia.ai.ConsentRequiredException;
import de.tum.cit.hestia.ai.JobLimitExceededException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice(basePackages = "de.tum.cit.hestia.assessment")
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException ex) {
        return error(ex.status().value(), ex.getMessage());
    }

    @ExceptionHandler(AiExceptions.RateLimitException.class)
    public ResponseEntity<Map<String, Object>> handleRate(AiExceptions.RateLimitException ex) {
        return error(HttpStatus.TOO_MANY_REQUESTS.value(), ex.getMessage());
    }

    @ExceptionHandler(AiExceptions.PaymentRequiredException.class)
    public ResponseEntity<Map<String, Object>> handlePayment(AiExceptions.PaymentRequiredException ex) {
        return error(HttpStatus.PAYMENT_REQUIRED.value(), ex.getMessage());
    }

    @ExceptionHandler(AiExceptions.ProviderException.class)
    public ResponseEntity<Map<String, Object>> handleProvider(AiExceptions.ProviderException ex) {
        // A retired model answers 410, as the parse pre-flight does; everything else is a bad gateway.
        int status = ex.status() == HttpStatus.GONE.value() ? HttpStatus.GONE.value() : HttpStatus.BAD_GATEWAY.value();
        return error(status, ex.getMessage());
    }

    /** An external model needs the user's consent first; the client asks, then retries (428). */
    @ExceptionHandler(ConsentRequiredException.class)
    public ResponseEntity<Map<String, Object>> handleConsent(ConsentRequiredException ex) {
        return coded(HttpStatus.PRECONDITION_REQUIRED.value(), CODE_CONSENT_REQUIRED,
            "Consent required: " + ex.scope());
    }

    /** The user reached the daily limit of a job purpose (429). */
    @ExceptionHandler(JobLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleJobLimit(JobLimitExceededException ex) {
        return coded(HttpStatus.TOO_MANY_REQUESTS.value(), CODE_JOB_LIMIT_EXCEEDED, ex.getReason());
    }

    /** Bean validation (@Valid) failures → 400 with the first field error, in the standard body shape. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        var fieldError = ex.getBindingResult().getFieldErrors().stream().findFirst();
        String message = fieldError
            .map(f -> f.getField() + " " + f.getDefaultMessage())
            .orElse("Invalid request");
        return error(HttpStatus.BAD_REQUEST.value(), message);
    }

    /** Unparseable / mistyped JSON body → 400 instead of Spring's default 500-ish payload. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        return error(HttpStatus.BAD_REQUEST.value(), "Malformed request body");
    }

    /**
     * DB constraint violations (duplicate keys from racing upserts, FK misses)
     * → 409: the request was well-formed but conflicts with current state.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleIntegrity(DataIntegrityViolationException ex) {
        return error(HttpStatus.CONFLICT.value(), "Conflicting update, please retry");
    }

    public static final String CODE_CONSENT_REQUIRED = "CONSENT_REQUIRED";
    public static final String CODE_JOB_LIMIT_EXCEEDED = "JOB_LIMIT_EXCEEDED";

    private static ResponseEntity<Map<String, Object>> coded(int status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message == null ? "Request failed" : message, "code", code));
    }

    private static ResponseEntity<Map<String, Object>> error(int status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message == null ? "Request failed" : message));
    }
}
