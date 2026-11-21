package de.tum.cit.hestia.core.internal.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.saml2.core.Saml2ParameterNames;
import org.springframework.security.saml2.provider.service.authentication.AbstractSaml2AuthenticationRequest;
import org.springframework.http.ResponseCookie;
import org.springframework.security.saml2.provider.service.web.Saml2AuthenticationRequestRepository;

/**
 * Keeps the SAML authentication requests the server sent, keyed by their RelayState, in memory
 * instead of in the HTTP session. The identity provider answers with a cross-site POST, and the
 * session cookie ({@code SameSite=Lax}) does not come with it; Spring then finds no request for the
 * response's InResponseTo and rejects the login. The RelayState does come back.
 *
 * <p>A request is bound to the browser that started the login: starting it sets the cookie
 * {@value #COOKIE} (a random nonce, {@code SameSite=None} so that it comes with the identity
 * provider's POST, {@code HttpOnly}, {@code Secure}, path only the assertion consumer service), and
 * only the nonce's hash is kept with the request. A response is matched to its request only when the
 * same cookie comes back. Otherwise an attacker could log in at an identity provider, keep the
 * response and its RelayState, and post them from a victim's browser, which would then be logged in
 * as the attacker (login CSRF). The cookie is not sent to the login start, so a new login in the
 * same browser replaces it: only the login started last in a browser can finish.
 *
 * <p>Entries expire after {@code ttl}; at most {@code capacity} are kept (the oldest go first), so
 * calling the login start URL in a loop cannot exhaust memory. The entries live in this server's
 * memory: Hestia must run as a single instance (or with sticky sessions per login), and a restart
 * cancels the logins in flight.
 */
final class RelayStateAuthenticationRequests implements Saml2AuthenticationRequestRepository<AbstractSaml2AuthenticationRequest> {

    static final String COOKIE = "HESTIA_SAML_NONCE";
    private static final String ACS_PATH = "/login/saml2/sso/";
    private static final SecureRandom RANDOM = new SecureRandom();

    private record Entry(AbstractSaml2AuthenticationRequest request, byte[] nonceHash, Instant expires) {}

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
        return valid(entry, request) ? entry.request() : null;
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
        String nonce = newNonce();
        // The identity provider posts to the assertion consumer service under the client's prefix,
        // which the forwarded-header handling puts into the context path.
        response.addHeader("Set-Cookie", ResponseCookie.from(COOKIE, nonce)
                .path(request.getContextPath() + ACS_PATH)
                .maxAge(ttl)
                .httpOnly(true)
                .secure(true)
                .sameSite("None")
                .build().toString());
        requests.put(authenticationRequest.getRelayState(),
                new Entry(authenticationRequest, hash(nonce), clock.instant().plus(ttl)));
    }

    @Override
    public AbstractSaml2AuthenticationRequest removeAuthenticationRequest(HttpServletRequest request,
            HttpServletResponse response) {
        String relayState = request.getParameter(Saml2ParameterNames.RELAY_STATE);
        if (relayState == null) {
            return null;
        }
        Entry entry = requests.get(relayState);
        if (!valid(entry, request)) {
            // Another browser's RelayState stays for its owner.
            return null;
        }
        requests.remove(relayState, entry);
        return entry.request();
    }

    int size() {
        return requests.size();
    }

    private boolean valid(Entry entry, HttpServletRequest request) {
        return entry != null && !expired(entry)
                && nonce(request).map(n -> MessageDigest.isEqual(hash(n), entry.nonceHash())).orElse(false);
    }

    private static Optional<String> nonce(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        return cookies == null ? Optional.empty() : Arrays.stream(cookies)
                .filter(c -> COOKIE.equals(c.getName()))
                .map(Cookie::getValue)
                .filter(v -> v != null && v.matches("[A-Za-z0-9_-]{43}"))
                .findFirst();
    }

    private static String newNonce() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] hash(String nonce) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(nonce.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
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
