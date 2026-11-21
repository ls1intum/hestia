package de.tum.cit.hestia.core.internal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.saml2.provider.service.authentication.AbstractSaml2AuthenticationRequest;

class RelayStateAuthenticationRequestsTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-21T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private final RelayStateAuthenticationRequests requests =
            new RelayStateAuthenticationRequests(Duration.ofMinutes(15), 10, clock);

    private static AbstractSaml2AuthenticationRequest request(String relayState) {
        AbstractSaml2AuthenticationRequest r = mock(AbstractSaml2AuthenticationRequest.class);
        when(r.getRelayState()).thenReturn(relayState);
        return r;
    }

    /** Starts a login in a browser that sends {@code cookie} (may be null); returns the Set-Cookie header. */
    private String start(AbstractSaml2AuthenticationRequest sent, Cookie cookie, String contextPath) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", contextPath + "/saml2/authenticate/tum");
        r.setContextPath(contextPath);
        if (cookie != null) {
            r.setCookies(cookie);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        requests.saveAuthenticationRequest(sent, r, response);
        return response.getHeader("Set-Cookie");
    }

    private Cookie start(AbstractSaml2AuthenticationRequest sent) {
        return nonce(start(sent, null, ""));
    }

    private static Cookie nonce(String setCookie) {
        Matcher m = Pattern.compile(RelayStateAuthenticationRequests.COOKIE + "=([^;]+)").matcher(setCookie);
        assertThat(m.find()).isTrue();
        return new Cookie(RelayStateAuthenticationRequests.COOKIE, m.group(1));
    }

    private static MockHttpServletRequest answer(String relayState, Cookie cookie) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/login/saml2/sso/tum");
        if (relayState != null) {
            r.setParameter("RelayState", relayState);
        }
        if (cookie != null) {
            r.setCookies(cookie);
        }
        return r;
    }

    @Test
    void findsTheRequestByRelayStateWithoutASession() {
        AbstractSaml2AuthenticationRequest sent = request("rs-1");
        Cookie browser = start(sent);

        assertThat(requests.loadAuthenticationRequest(answer("rs-1", browser))).isSameAs(sent);
        assertThat(requests.loadAuthenticationRequest(answer("rs-2", browser))).isNull();
        assertThat(requests.loadAuthenticationRequest(answer(null, browser))).isNull();
        assertThat(requests.removeAuthenticationRequest(answer("rs-1", browser), new MockHttpServletResponse())).isSameAs(sent);
        assertThat(requests.loadAuthenticationRequest(answer("rs-1", browser))).isNull();
    }

    @Test
    void theNonceCookieComesWithTheCrossSitePostToTheAcsOnly() {
        String header = start(request("rs-1"), null, "/workshopper");
        assertThat(header).contains("Path=/workshopper/login/saml2/sso/", "HttpOnly", "Secure", "SameSite=None",
                "Max-Age=900");
    }

    @Test
    void anotherBrowserCannotUseTheResponse() {
        AbstractSaml2AuthenticationRequest sent = request("rs-1");
        start(sent);
        Cookie attacker = start(request("rs-attacker"));

        // The attacker's own login response and RelayState, posted from the victim's browser (other or no cookie)
        assertThat(requests.loadAuthenticationRequest(answer("rs-attacker", null))).isNull();
        assertThat(requests.loadAuthenticationRequest(answer("rs-1", attacker))).isNull();
        assertThat(requests.removeAuthenticationRequest(answer("rs-1", attacker), new MockHttpServletResponse())).isNull();
        assertThat(requests.loadAuthenticationRequest(answer("rs-1",
                new Cookie(RelayStateAuthenticationRequests.COOKIE, "x".repeat(43))))).isNull();
    }

    @Test
    void everyLoginGetsItsOwnNonce() {
        AbstractSaml2AuthenticationRequest first = request("rs-1");
        AbstractSaml2AuthenticationRequest second = request("rs-2");
        Cookie one = start(first);
        Cookie two = start(second);
        assertThat(two.getValue()).isNotEqualTo(one.getValue());

        assertThat(requests.loadAuthenticationRequest(answer("rs-1", two))).isNull();
        assertThat(requests.loadAuthenticationRequest(answer("rs-2", two))).isSameAs(second);
    }

    @Test
    void requestsExpire() {
        Cookie browser = start(request("rs-1"));
        now.set(now.get().plus(Duration.ofMinutes(16)));
        assertThat(requests.loadAuthenticationRequest(answer("rs-1", browser))).isNull();
    }

    @Test
    void keepsAtMostTheCapacity() {
        Cookie first = null;
        Cookie last = null;
        for (int i = 0; i < 100; i++) {
            now.set(now.get().plusSeconds(1));
            last = start(request("rs-" + i));
            first = first == null ? last : first;
        }
        assertThat(requests.size()).isLessThanOrEqualTo(10);
        assertThat(requests.loadAuthenticationRequest(answer("rs-99", last))).isNotNull();
        assertThat(requests.loadAuthenticationRequest(answer("rs-0", first))).isNull();
    }
}
