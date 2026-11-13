package de.tum.cit.hestia.assessment.error;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;
import java.io.UncheckedIOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The filter trades log noise for the risk of hiding a real failure, so the
 * discrimination has to be exact. Anything that is not a disconnect the client
 * caused must still propagate.
 */
class ClientAbortFilterTest {

    private static class ClientAbortException extends IOException {
        ClientAbortException() { super("something Tomcat-specific"); }
    }

    private static class AsyncRequestNotUsableException extends RuntimeException {
        AsyncRequestNotUsableException() { super("Response not usable after response errors."); }
    }

    @Test
    void treatsABrokenPipeAsAClientDisconnect() {
        assertThat(ClientAbortFilter.isClientDisconnect(new IOException("Broken pipe"))).isTrue();
    }

    @Test
    void treatsAConnectionResetAsAClientDisconnect() {
        assertThat(ClientAbortFilter.isClientDisconnect(
            new IOException("Connection reset by peer"))).isTrue();
    }

    @Test
    void recognisesTheContainerAndSpringTypesByName() {
        assertThat(ClientAbortFilter.isClientDisconnect(new ClientAbortException())).isTrue();
        assertThat(ClientAbortFilter.isClientDisconnect(
            new AsyncRequestNotUsableException())).isTrue();
    }

    @Test
    void findsTheDisconnectDeepInACauseChain() {
        Exception buried = new IllegalStateException("wrapper",
            new UncheckedIOException(new IOException("Broken pipe")));

        assertThat(ClientAbortFilter.isClientDisconnect(buried)).isTrue();
    }

    @Test
    void doesNotSwallowARealBug() {
        assertThat(ClientAbortFilter.isClientDisconnect(
            new NullPointerException("oops"))).isFalse();
        assertThat(ClientAbortFilter.isClientDisconnect(
            new IllegalArgumentException("bad input"))).isFalse();
    }

    @Test
    void doesNotSwallowAnIoErrorThatIsNotADisconnect() {
        // Disk full, permission denied — these are ours to fix, not the client's.
        assertThat(ClientAbortFilter.isClientDisconnect(
            new IOException("No space left on device"))).isFalse();
        assertThat(ClientAbortFilter.isClientDisconnect(
            new IOException((String) null))).isFalse();
    }

    /** Java forbids self-causation, but a cycle through two throwables is legal. */
    private static class Cyclic extends RuntimeException {
        private Throwable other;
        Cyclic(String message) { super(message); }
        @Override public synchronized Throwable getCause() { return other; }
    }

    @Test
    void survivesACyclicCauseChain() {
        // A malformed exception must not spin the request thread forever.
        Cyclic a = new Cyclic("a");
        Cyclic b = new Cyclic("b");
        a.other = b;
        b.other = a;

        assertThat(ClientAbortFilter.isClientDisconnect(a)).isFalse();
    }

    private static boolean skipped(MockHttpServletRequest request) {
        return new ClientAbortFilter() {
            boolean check() { return shouldNotFilter(request); }
        }.check();
    }

    @Test
    void actsOnlyOnTheAssessmentModulesRequests() {
        assertThat(skipped(new MockHttpServletRequest("GET", "/examlense/api/exams"))).isFalse();
        assertThat(skipped(new MockHttpServletRequest("GET", "/learninggoalhub/api/courses"))).isTrue();
        assertThat(skipped(new MockHttpServletRequest("GET", "/workshopper/api/x"))).isTrue();
    }

    @Test
    void errorDispatchesAreJudgedByTheOriginalPath() {
        MockHttpServletRequest own = new MockHttpServletRequest("GET", "/error");
        own.setAttribute("jakarta.servlet.error.request_uri", "/examlense/api/exams/1/events");
        assertThat(skipped(own)).isFalse();

        MockHttpServletRequest other = new MockHttpServletRequest("GET", "/error");
        other.setAttribute("jakarta.servlet.error.request_uri", "/learninggoalhub/api/courses");
        assertThat(skipped(other)).isTrue();
    }
}
