package de.tum.cit.hestia.teaching.flow;

import java.net.http.HttpRequest;

/**
 * The ONLY place that knows how HTTP flow tests authenticate. The "local" Spring profile signs every
 * request in as a dev user (headers X-Dev-User / X-Dev-Org choose another one); writes carry a CSRF
 * token by double submit (cookie XSRF-TOKEN and header X-XSRF-TOKEN). If auth changes, edit this class.
 */
final class FlowAuth {

    /** Spring profile under which every request counts as signed in. */
    static final String SIGNED_IN_PROFILE = "local";

    /** Spring profile with no sign-in bypass, for the "unauthenticated call is rejected" test. */
    static final String ANONYMOUS_PROFILE = "flow-anonymous";

    private FlowAuth() {}

    private static final String CSRF = "flow-csrf-token";

    /** Adds the CSRF token every caller needs for writes. */
    static HttpRequest.Builder csrf(HttpRequest.Builder request) {
        return request.header("Cookie", "XSRF-TOKEN=" + CSRF).header("X-XSRF-TOKEN", CSRF);
    }

    /** Adds whatever credentials a signed-in caller needs; null user/org keep the dev defaults. */
    static HttpRequest.Builder signIn(HttpRequest.Builder request, String user, String org) {
        csrf(request);
        if (user != null) request.header("X-Dev-User", user);
        if (org != null) request.header("X-Dev-Org", org);
        return request;
    }
}
