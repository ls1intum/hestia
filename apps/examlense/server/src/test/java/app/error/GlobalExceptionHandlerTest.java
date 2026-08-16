package app.error;

import app.ai.AiExceptions;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The handler maps domain exceptions to clean JSON bodies. The security-relevant
 * property is that the response carries only the human message — never a stack
 * trace or internal detail.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void apiExceptionKeepsItsStatusAndMessage() {
        ResponseEntity<Map<String, Object>> res =
            handler.handleApi(new ApiException(HttpStatus.FORBIDDEN, "Forbidden"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody()).isEqualTo(Map.of("error", "Forbidden"));
    }

    @Test
    void rateLimitMapsTo429() {
        ResponseEntity<Map<String, Object>> res =
            handler.handleRate(new AiExceptions.RateLimitException("slow down"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(res.getBody()).containsEntry("error", "slow down");
    }

    @Test
    void paymentRequiredMapsTo402() {
        ResponseEntity<Map<String, Object>> res =
            handler.handlePayment(new AiExceptions.PaymentRequiredException("out of credits"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
    }

    @Test
    void providerErrorMapsToBadGateway() {
        ResponseEntity<Map<String, Object>> res =
            handler.handleProvider(new AiExceptions.ProviderException("upstream boom", 500));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void bodyExposesOnlyTheErrorMessageNoInternals() {
        ResponseEntity<Map<String, Object>> res =
            handler.handleApi(new ApiException(HttpStatus.NOT_FOUND, "Exam not found"));

        assertThat(res.getBody()).containsOnlyKeys("error");
    }
}
