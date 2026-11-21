package de.tum.cit.hestia.core.internal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
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

    private static MockHttpServletRequest answer(String relayState) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/login/saml2/sso/tum");
        if (relayState != null) {
            r.setParameter("RelayState", relayState);
        }
        return r;
    }

    @Test
    void findsTheRequestByRelayStateWithoutASession() {
        AbstractSaml2AuthenticationRequest sent = request("rs-1");
        requests.saveAuthenticationRequest(sent, new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(requests.loadAuthenticationRequest(answer("rs-1"))).isSameAs(sent);
        assertThat(requests.loadAuthenticationRequest(answer("rs-2"))).isNull();
        assertThat(requests.loadAuthenticationRequest(answer(null))).isNull();
        assertThat(requests.removeAuthenticationRequest(answer("rs-1"), new MockHttpServletResponse())).isSameAs(sent);
        assertThat(requests.loadAuthenticationRequest(answer("rs-1"))).isNull();
    }

    @Test
    void requestsExpire() {
        requests.saveAuthenticationRequest(request("rs-1"), new MockHttpServletRequest(), new MockHttpServletResponse());
        now.set(now.get().plus(Duration.ofMinutes(16)));
        assertThat(requests.loadAuthenticationRequest(answer("rs-1"))).isNull();
    }

    @Test
    void keepsAtMostTheCapacity() {
        for (int i = 0; i < 100; i++) {
            now.set(now.get().plusSeconds(1));
            requests.saveAuthenticationRequest(request("rs-" + i), new MockHttpServletRequest(), new MockHttpServletResponse());
        }
        assertThat(requests.size()).isLessThanOrEqualTo(10);
        assertThat(requests.loadAuthenticationRequest(answer("rs-99"))).isNotNull();
        assertThat(requests.loadAuthenticationRequest(answer("rs-0"))).isNull();
    }
}
