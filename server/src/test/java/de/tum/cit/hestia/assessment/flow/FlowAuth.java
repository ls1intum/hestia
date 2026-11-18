package de.tum.cit.hestia.assessment.flow;

import de.tum.cit.hestia.assessment.AbstractIntegrationTest;
import java.net.http.HttpRequest;

/**
 * The ONE place that knows how a test request authenticates. Today: the shared
 * bootstrap bearer token that {@link AbstractIntegrationTest} configures. When
 * the token system is replaced by a session login, only {@link #apply} (and, if
 * a login call is needed first, a bit of state here) changes.
 */
final class FlowAuth {

    private FlowAuth() {}

    /** Authenticate the request as the standard test user. */
    static HttpRequest.Builder apply(HttpRequest.Builder request) {
        return request.header("Authorization", "Bearer " + AbstractIntegrationTest.TEST_TOKEN);
    }

    /** Present a credential the server must reject. Used for the 401 checks. */
    static HttpRequest.Builder applyInvalid(HttpRequest.Builder request) {
        return request.header("Authorization", "Bearer not-a-valid-token");
    }
}
