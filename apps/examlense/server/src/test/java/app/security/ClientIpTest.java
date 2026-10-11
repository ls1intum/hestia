package app.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which entry of {@code X-Forwarded-For} is the caller's real address.
 *
 * <p>This is the input to every per-IP limit, and both ways of getting it wrong
 * are silent: reading a client-supplied entry makes the limits evadable with one
 * header, while reading our own proxy's entry gives the same value for everyone
 * and turns a per-IP cap into a global one. Neither shows up as an error.
 */
class ClientIpTest {

    private static final int TRAEFIK_AND_NGINX = 2;

    private static MockHttpServletRequest request(String forwardedFor) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("172.18.0.9"); // the immediate peer: nginx's view of Traefik
        if (forwardedFor != null) req.addHeader("X-Forwarded-For", forwardedFor);
        return req;
    }

    private static MockHttpServletRequest withClientIpHeader(String clientIp, String forwardedFor) {
        MockHttpServletRequest req = request(forwardedFor);
        if (clientIp != null) req.addHeader("X-Client-Ip", clientIp);
        return req;
    }

    // --- X-Client-Ip: what nginx resolves, and the only trustworthy source --------

    @Test
    void prefersTheAddressNginxResolved() {
        assertThat(ClientIp.of(
                withClientIpHeader("203.0.113.7", "9.9.9.9, 203.0.113.7, 10.0.1.2"),
                true, TRAEFIK_AND_NGINX))
            .isEqualTo("203.0.113.7");
    }

    /**
     * nginx sets this header with proxy_set_header, which overwrites whatever the
     * client sent — so trusting it is safe only because the request came through
     * nginx. This pins that we read it rather than re-deriving from the chain.
     */
    @Test
    void aForgedForwardedChainCannotOverrideIt() {
        String withJunk = ClientIp.of(
            withClientIpHeader("203.0.113.7", "1.1.1.1, 2.2.2.2, 3.3.3.3"),
            true, TRAEFIK_AND_NGINX);

        assertThat(withJunk).isEqualTo("203.0.113.7");
    }

    @Test
    void isIgnoredWithoutAProxy() {
        assertThat(ClientIp.of(withClientIpHeader("1.2.3.4", null), false, TRAEFIK_AND_NGINX))
            .isEqualTo("172.18.0.9");
    }

    @Test
    void blankFallsThroughToTheForwardedChain() {
        assertThat(ClientIp.of(
                withClientIpHeader("   ", "203.0.113.7, 10.0.1.2"), true, TRAEFIK_AND_NGINX))
            .isEqualTo("203.0.113.7");
    }

    // --- X-Forwarded-For fallback, for a proxy that does not set X-Client-Ip ------

    @Test
    void withoutAProxyTheForwardedHeaderIsIgnoredEntirely() {
        // A direct caller can set the header freely, so it must not be consulted.
        MockHttpServletRequest req = request("1.2.3.4");

        assertThat(ClientIp.of(req, false, TRAEFIK_AND_NGINX)).isEqualTo("172.18.0.9");
    }

    @Test
    void readsTheClientAddressOurProxiesAppended() {
        // Traefik appended the client, nginx appended Traefik.
        MockHttpServletRequest req = request("203.0.113.7, 10.0.1.2");

        assertThat(ClientIp.of(req, true, TRAEFIK_AND_NGINX)).isEqualTo("203.0.113.7");
    }

    /** The regression this fix exists for. */
    @Test
    void aForgedLeadingEntryDoesNotChangeTheResult() {
        String real = ClientIp.of(request("203.0.113.7, 10.0.1.2"), true, TRAEFIK_AND_NGINX);
        String spoofed = ClientIp.of(
            request("9.9.9.9, 203.0.113.7, 10.0.1.2"), true, TRAEFIK_AND_NGINX);

        assertThat(spoofed).isEqualTo(real).isEqualTo("203.0.113.7");
    }

    @Test
    void noNumberOfForgedEntriesShiftsTheResult() {
        assertThat(ClientIp.of(
                request("1.1.1.1, 2.2.2.2, 3.3.3.3, 203.0.113.7, 10.0.1.2"),
                true, TRAEFIK_AND_NGINX))
            .isEqualTo("203.0.113.7");
    }

    /** Two callers behind the same proxies must not collapse onto the proxy's address. */
    @Test
    void differentClientsResolveToDifferentAddresses() {
        String a = ClientIp.of(request("203.0.113.7, 10.0.1.2"), true, TRAEFIK_AND_NGINX);
        String b = ClientIp.of(request("203.0.113.8, 10.0.1.2"), true, TRAEFIK_AND_NGINX);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void handlesASingleProxyTopology() {
        assertThat(ClientIp.of(request("203.0.113.7"), true, 1)).isEqualTo("203.0.113.7");
        assertThat(ClientIp.of(request("9.9.9.9, 203.0.113.7"), true, 1)).isEqualTo("203.0.113.7");
    }

    @Test
    void toleratesWhitespaceAndEmptyEntries() {
        assertThat(ClientIp.of(request("  203.0.113.7 ,, 10.0.1.2  "), true, TRAEFIK_AND_NGINX))
            .isEqualTo("203.0.113.7");
    }

    @Test
    void fallsBackToThePeerWhenTheHeaderIsAbsentOrEmpty() {
        assertThat(ClientIp.of(request(null), true, TRAEFIK_AND_NGINX)).isEqualTo("172.18.0.9");
        assertThat(ClientIp.of(request("   "), true, TRAEFIK_AND_NGINX)).isEqualTo("172.18.0.9");
    }

    /**
     * A chain shorter than configured means the hop count and the real topology
     * disagree. Clamping keeps the limiter working on the best available value
     * rather than failing every request or refusing every registration.
     */
    @Test
    void aShorterChainThanConfiguredClampsToTheLeftmostEntry() {
        assertThat(ClientIp.of(request("203.0.113.7"), true, TRAEFIK_AND_NGINX))
            .isEqualTo("203.0.113.7");
    }

    @Test
    void aHopCountOfZeroIsTreatedAsOneRatherThanIndexingPastTheEnd() {
        assertThat(ClientIp.of(request("203.0.113.7, 10.0.1.2"), true, 0)).isEqualTo("10.0.1.2");
    }
}
