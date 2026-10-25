package de.tum.cit.hestia.learninggoalhub.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Caps the requests that start model calls at a fixed number per client and time window, and
 * answers the rest with 429. Every such request spends the deployment's inference quota and can
 * run for minutes, so without a cap a single client could exhaust it for everyone.
 *
 * <p>Clients are counted by address (the forwarded client address behind the proxies). Requests
 * authenticated with a service key are not counted: the consuming apps are trusted, and their
 * users would otherwise share one address and one allowance.
 */
final class LlmRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LlmRateLimitFilter.class);

    /** Above this many tracked clients, windows that have ended are dropped. */
    private static final int SWEEP_THRESHOLD = 10_000;

    private final RequestMatcher llmRequests;
    private final int limit;
    private final long windowMillis;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    LlmRateLimitFilter(RequestMatcher llmRequests, int limit, Duration window, Clock clock) {
        this.llmRequests = llmRequests;
        this.limit = limit;
        this.windowMillis = window.toMillis();
        this.clock = clock;
    }

    private record Window(long start, AtomicInteger count) {
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!llmRequests.matches(request) || isService()) {
            chain.doFilter(request, response);
            return;
        }
        long retryAfterSeconds = tryAcquire(request.getRemoteAddr());
        if (retryAfterSeconds == 0) {
            chain.doFilter(request, response);
            return;
        }
        log.warn("Rate limit reached for {}: {} {}", request.getRemoteAddr(), request.getMethod(),
                request.getRequestURI());
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":\"TOO_MANY_REQUESTS\",\"message\":\"Too many AI requests. "
                + "Try again in " + retryAfterSeconds + " seconds.\"}");
    }

    /** Returns 0 when the request may proceed, otherwise the seconds until the client's window resets. */
    long tryAcquire(String client) {
        long now = clock.millis();
        if (windows.size() > SWEEP_THRESHOLD) {
            windows.values().removeIf(window -> now - window.start() >= windowMillis);
        }
        Window window = windows.compute(client, (key, current) ->
                current == null || now - current.start() >= windowMillis
                        ? new Window(now, new AtomicInteger())
                        : current);
        if (window.count().incrementAndGet() <= limit) {
            return 0;
        }
        return Math.max(1, (window.start() + windowMillis - now + 999) / 1000);
    }

    private static boolean isService() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> ("ROLE_" + ServiceApiKeyFilter.ROLE).equals(authority.getAuthority()));
    }
}
