package de.tum.cit.hestia.ai;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** The user has reached the daily limit of a job purpose (HTTP 429). */
public class JobLimitExceededException extends ResponseStatusException {

    private final String purpose;
    private final int limit;

    public JobLimitExceededException(String purpose, int limit) {
        super(HttpStatus.TOO_MANY_REQUESTS,
                "Daily limit reached for %s (%d per day). Try again tomorrow.".formatted(purpose, limit));
        this.purpose = purpose;
        this.limit = limit;
    }

    public String purpose() {
        return purpose;
    }

    public int limit() {
        return limit;
    }
}
