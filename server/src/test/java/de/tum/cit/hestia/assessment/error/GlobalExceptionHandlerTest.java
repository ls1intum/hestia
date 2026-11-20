package de.tum.cit.hestia.assessment.error;

import de.tum.cit.hestia.ai.AiExceptions;
import de.tum.cit.hestia.ai.ConsentRequiredException;
import de.tum.cit.hestia.ai.ConsentScope;
import de.tum.cit.hestia.ai.JobLimitExceededException;
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
    void retiredModelMapsTo410() {
        ResponseEntity<Map<String, Object>> res =
            handler.handleProvider(new AiExceptions.ProviderException("retired", 410));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.GONE);
    }

    @Test
    void missingConsentMapsTo428WithAStableCode() {
        ResponseEntity<Map<String, Object>> res =
            handler.handleConsent(new ConsentRequiredException(ConsentScope.EXTERNAL_MODELS));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
        assertThat(res.getBody()).containsEntry("code", "CONSENT_REQUIRED").containsKey("error");
    }

    @Test
    void jobLimitMapsTo429WithAStableCode() {
        ResponseEntity<Map<String, Object>> res =
            handler.handleJobLimit(new JobLimitExceededException("exam-parse", 10));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(res.getBody()).containsEntry("code", "JOB_LIMIT_EXCEEDED");
        assertThat((String) res.getBody().get("error")).contains("exam-parse");
    }

    @Test
    void bodyExposesOnlyTheErrorMessageNoInternals() {
        ResponseEntity<Map<String, Object>> res =
            handler.handleApi(new ApiException(HttpStatus.NOT_FOUND, "Exam not found"));

        assertThat(res.getBody()).containsOnlyKeys("error");
    }
}
