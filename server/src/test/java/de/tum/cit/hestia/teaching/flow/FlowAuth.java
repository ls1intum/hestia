package de.tum.cit.hestia.teaching.flow;

import java.net.http.HttpRequest;

/**
 * The ONLY place that knows how HTTP flow tests authenticate. Today: the "local" Spring profile signs
 * every request in as a fixed dev user, so no header is needed. If auth changes, edit this class.
 */
final class FlowAuth {

    /** Spring profile under which every request counts as signed in. */
    static final String SIGNED_IN_PROFILE = "local";

    /** Spring profile with no sign-in bypass, for the "unauthenticated call is rejected" test. */
    static final String ANONYMOUS_PROFILE = "flow-anonymous";

    private FlowAuth() {}

    /** Adds whatever credentials a signed-in caller needs. */
    static HttpRequest.Builder signIn(HttpRequest.Builder request) {
        return request;
    }
}
