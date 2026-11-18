package de.tum.cit.hestia.assessment.flow;

import java.net.http.HttpRequest;

/**
 * The ONE place that knows how a test request authenticates. The "local" profile signs every request
 * in as the dev user of the dev organisation (see core's dev login); writes still need the CSRF
 * double submit, so every request carries the cookie {@code XSRF-TOKEN} and the matching header
 * {@code X-XSRF-TOKEN}.
 */
final class FlowAuth {

    /** Spring profile under which every request counts as logged in. */
    static final String SIGNED_IN_PROFILE = "local";

    private static final String CSRF = "flow-csrf-token";

    private FlowAuth() {}

    /** Authenticate the request as the standard test user. */
    static HttpRequest.Builder apply(HttpRequest.Builder request) {
        return request.header("Cookie", "XSRF-TOKEN=" + CSRF).header("X-XSRF-TOKEN", CSRF);
    }
}
