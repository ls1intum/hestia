package de.tum.cit.hestia.learninggoalhub.web;

import java.io.IOException;
import java.util.concurrent.CompletionException;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.dao.DataAccessException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;

/**
 * The failure text of an extraction or tree rebuild. It is stored on the run and returned to anyone
 * who polls the status, so provider, network and database errors are replaced by a fixed
 * description; the exception itself goes to the log where it is caught.
 */
public final class ExtractionErrors {

    private ExtractionErrors() {
    }

    /** A failure whose message is already written for the instructor and is shown as it is. */
    public static final class PublicFailure extends IllegalStateException {
        public PublicFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static String describe(Throwable error) {
        if (error instanceof PublicFailure) {
            return error.getMessage();
        }
        if (error instanceof ResponseStatusException rse && rse.getReason() != null) {
            return rse.getReason();
        }
        for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof TransientAiException) {
                return "The language model is unavailable or rate-limited right now. Try again in a few minutes.";
            }
            if (cause instanceof NonTransientAiException) {
                return "The language model rejected a request.";
            }
            if (cause instanceof ResourceAccessException || cause instanceof IOException) {
                return "The language model could not be reached or did not answer in time.";
            }
            if (cause instanceof DataAccessException) {
                return "The results could not be saved.";
            }
        }
        Throwable top = error;
        while (top instanceof CompletionException && top.getCause() != null) {
            top = top.getCause();
        }
        return top.getMessage() == null || top.getMessage().isBlank()
                ? top.getClass().getSimpleName()
                : top.getMessage();
    }
}
