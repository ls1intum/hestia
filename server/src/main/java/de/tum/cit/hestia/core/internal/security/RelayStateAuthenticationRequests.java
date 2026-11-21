package de.tum.cit.hestia.core.internal.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.saml2.core.Saml2ParameterNames;
import org.springframework.security.saml2.provider.service.authentication.AbstractSaml2AuthenticationRequest;
import org.springframework.security.saml2.provider.service.web.Saml2AuthenticationRequestRepository;

/**
 * Keeps the SAML authentication requests the server sent, keyed by their RelayState, in memory
 * instead of in the HTTP session. The identity provider answers with a cross-site POST, and the
 * session cookie ({@code SameSite=Lax}) does not come with it; Spring then finds no request for the
 * response's InResponseTo and rejects the login. The RelayState does come back.
 *
 * <p>Entries expire after {@code ttl}; at most {@code capacity} are kept (the oldest go first), so
 * calling the login start URL in a loop cannot exhaust memory. One server instance holds them,
 * which is how Hestia runs.
 */
final class RelayStateAuthenticationRequests implements Saml2AuthenticationRequestRepository<AbstractSaml2AuthenticationRequest> {

    private record Entry(AbstractSaml2AuthenticationRequest request, Instant expires) {}

    private final Map<String, Entry> requests = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final int capacity;
    private final Clock clock;

    RelayStateAuthenticationRequests(Duration ttl, int capacity, Clock clock) {
        this.ttl = ttl;
        this.capacity = capacity;
        this.clock = clock;
    }

    @Override
    public AbstractSaml2AuthenticationRequest loadAuthenticationRequest(HttpServletRequest request) {
        String relayState = request.getParameter(Saml2ParameterNames.RELAY_STATE);
        if (relayState == null) {
            return null;
        }
        Entry entry = requests.get(relayState);
        return entry == null || expired(entry) ? null : entry.request();
    }

    @Override
    public void saveAuthenticationRequest(AbstractSaml2AuthenticationRequest authenticationRequest,
            HttpServletRequest request, HttpServletResponse response) {
        if (authenticationRequest == null || authenticationRequest.getRelayState() == null) {
            return;
        }
        if (requests.size() >= capacity) {
            prune();
        }
        requests.put(authenticationRequest.getRelayState(), new Entry(authenticationRequest, clock.instant().plus(ttl)));
    }

    @Override
    public AbstractSaml2AuthenticationRequest removeAuthenticationRequest(HttpServletRequest request,
            HttpServletResponse response) {
        String relayState = request.getParameter(Saml2ParameterNames.RELAY_STATE);
        if (relayState == null) {
            return null;
        }
        Entry entry = requests.remove(relayState);
        return entry == null || expired(entry) ? null : entry.request();
    }

    int size() {
        return requests.size();
    }

    private boolean expired(Entry entry) {
        return !clock.instant().isBefore(entry.expires());
    }

    /** Drops expired entries; if still full, the ones that expire first. */
    private synchronized void prune() {
        requests.values().removeIf(this::expired);
        while (requests.size() >= capacity) {
            requests.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue((a, b) -> a.expires().compareTo(b.expires())))
                    .limit(Math.max(1, capacity / 10))
                    .map(Map.Entry::getKey)
                    .toList()
                    .forEach(requests::remove);
        }
    }
}
