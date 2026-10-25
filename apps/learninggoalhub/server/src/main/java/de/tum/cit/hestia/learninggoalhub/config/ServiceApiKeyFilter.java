package de.tum.cit.hestia.learninggoalhub.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates the other Hestia apps' servers by a shared key in {@value #HEADER}, one key per
 * caller. A matching key makes the request a {@code ROLE_SERVICE} principal named after the caller,
 * for this request only (nothing is stored in a session), and logs the caller.
 *
 * <p>Whether a service-only endpoint rejects a request without a key is up to the authorization
 * rules in {@link SecurityConfig}. Until they enforce it, this filter logs each request it would
 * reject, so the consumers' switch to sending their key can be followed in the log.
 */
final class ServiceApiKeyFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Api-Key";
    static final String ROLE = "SERVICE";

    private static final Logger log = LoggerFactory.getLogger(ServiceApiKeyFilter.class);

    private final Map<String, byte[]> keysByCaller = new LinkedHashMap<>();
    private final RequestMatcher serviceOnly;
    private final boolean enforced;

    /** Callers with a blank key are left out, so an unset secret never matches an empty header. */
    ServiceApiKeyFilter(Map<String, String> keysByCaller, RequestMatcher serviceOnly, boolean enforced) {
        keysByCaller.forEach((caller, key) -> {
            if (key != null && !key.isBlank()) {
                this.keysByCaller.put(caller, key.strip().getBytes(StandardCharsets.UTF_8));
            }
        });
        this.serviceOnly = serviceOnly;
        this.enforced = enforced;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader(HEADER);
        String caller = presented == null ? null : callerFor(presented);
        if (caller != null) {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new PreAuthenticatedAuthenticationToken(
                    caller, null, List.of(new SimpleGrantedAuthority("ROLE_" + ROLE))));
            SecurityContextHolder.setContext(context);
            log.info("API request from caller={}: {} {}", caller, request.getMethod(), request.getRequestURI());
        } else if (serviceOnly.matches(request)) {
            String reason = presented == null ? "no API key" : "unknown API key";
            if (enforced) {
                log.warn("Rejected {} {}: {}", request.getMethod(), request.getRequestURI(), reason);
            } else {
                log.warn("Would reject {} {} once service keys are enforced: {}",
                        request.getMethod(), request.getRequestURI(), reason);
            }
        } else if (presented != null) {
            log.warn("Unknown API key on {} {}", request.getMethod(), request.getRequestURI());
        }
        chain.doFilter(request, response);
    }

    private String callerFor(String presented) {
        byte[] candidate = presented.strip().getBytes(StandardCharsets.UTF_8);
        String match = null;
        for (Map.Entry<String, byte[]> entry : keysByCaller.entrySet()) {
            // Constant-time comparison, and every key is compared, so timing reveals nothing.
            if (MessageDigest.isEqual(candidate, entry.getValue())) {
                match = entry.getKey();
            }
        }
        return match;
    }
}
