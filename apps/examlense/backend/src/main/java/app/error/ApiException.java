package app.error;

import org.springframework.http.HttpStatus;

/** Throw from controllers/services to signal an HTTP error with a clean body. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
    public HttpStatus status() { return status; }
}
