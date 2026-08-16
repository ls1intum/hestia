package app.error;

import app.ai.AiExceptions;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
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
        return error(HttpStatus.BAD_GATEWAY.value(), ex.getMessage());
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

    private static ResponseEntity<Map<String, Object>> error(int status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message == null ? "Request failed" : message));
    }
}
