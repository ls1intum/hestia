package app.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiExceptionsTest {

    @Test
    void rateLimitAndServerErrorsAreTransient() {
        assertThat(AiExceptions.isTransient(new AiExceptions.RateLimitException("429"))).isTrue();
        assertThat(AiExceptions.isTransient(new AiExceptions.ProviderException("429", 429))).isTrue();
        assertThat(AiExceptions.isTransient(new AiExceptions.ProviderException("500", 500))).isTrue();
        assertThat(AiExceptions.isTransient(new AiExceptions.ProviderException("503", 503))).isTrue();
        assertThat(AiExceptions.isTransient(new AiExceptions.ProviderException("timeout", 0))).isTrue();
        assertThat(AiExceptions.isTransient(new AiExceptions.ProviderException("408", 408))).isTrue();
    }

    @Test
    void clientErrorsAndMalformedOutputAreNotTransient() {
        assertThat(AiExceptions.isTransient(new AiExceptions.ProviderException("bad key", 401))).isFalse();
        assertThat(AiExceptions.isTransient(new AiExceptions.ProviderException("bad schema", 400))).isFalse();
        assertThat(AiExceptions.isTransient(new AiExceptions.MalformedModelOutputException("no tool call"))).isFalse();
        assertThat(AiExceptions.isTransient(new RuntimeException("anything"))).isFalse();
    }

    @Test
    void malformedOutputIsStillAProviderException() {
        // GlobalExceptionHandler and legacy catch blocks branch on ProviderException.
        assertThat(new AiExceptions.MalformedModelOutputException("x"))
            .isInstanceOf(AiExceptions.ProviderException.class);
    }
}
