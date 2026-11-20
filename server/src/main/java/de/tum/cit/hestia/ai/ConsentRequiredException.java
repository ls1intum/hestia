package de.tum.cit.hestia.ai;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A call needs a consent the user has not given (HTTP 428, so a client can ask and retry). */
public class ConsentRequiredException extends ResponseStatusException {

    private final ConsentScope scope;

    public ConsentRequiredException(ConsentScope scope) {
        super(HttpStatus.PRECONDITION_REQUIRED, "Consent required: " + scope);
        this.scope = scope;
    }

    public ConsentScope scope() {
        return scope;
    }
}
