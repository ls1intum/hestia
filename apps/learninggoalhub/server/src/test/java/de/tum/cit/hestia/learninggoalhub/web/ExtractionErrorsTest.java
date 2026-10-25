package de.tum.cit.hestia.learninggoalhub.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;

class ExtractionErrorsTest {

    @Test
    void replacesProviderNetworkAndDatabaseErrorsWithAFixedText() {
        assertThat(ExtractionErrors.describe(new IllegalStateException("Topic tree synthesis failed",
                new TransientAiException("HTTP 429 - {\"error\":\"quota for key sk-123\"}"))))
                .isEqualTo("The language model is unavailable or rate-limited right now. Try again in a few minutes.");
        assertThat(ExtractionErrors.describe(new NonTransientAiException("HTTP 400 - model x not found")))
                .isEqualTo("The language model rejected a request.");
        assertThat(ExtractionErrors.describe(new CompletionException(
                new ResourceAccessException("I/O error on POST https://gateway/internal"))))
                .isEqualTo("The language model could not be reached or did not answer in time.");
        assertThat(ExtractionErrors.describe(new DataIntegrityViolationException("insert into learning_goal ...")))
                .isEqualTo("The results could not be saved.");
    }

    @Test
    void keepsTheApplicationsOwnMessages() {
        assertThat(ExtractionErrors.describe(new ExtractionErrors.PublicFailure("The learning goals were saved.",
                new TransientAiException("raw"))))
                .isEqualTo("The learning goals were saved.");
        assertThat(ExtractionErrors.describe(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Tree untouched.",
                new TransientAiException("raw"))))
                .isEqualTo("Tree untouched.");
        assertThat(ExtractionErrors.describe(new CompletionException(new IllegalStateException("1 session failed"))))
                .isEqualTo("1 session failed");
        assertThat(ExtractionErrors.describe(new IllegalStateException())).isEqualTo("IllegalStateException");
    }
}
