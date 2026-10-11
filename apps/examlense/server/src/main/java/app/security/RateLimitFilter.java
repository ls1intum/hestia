package app.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Dependency-free per-IP fixed-window rate limiter — coarse bot/overload
 * protection, not a precise quota system. Counts requests per client IP within
 * a rolling fixed window; over the limit returns 429.
 *
 * Client IPs come from {@link ClientIp}, shared with the registration cap.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final int MAX_TRACKED_IPS = 50_000;

    private final int maxRequests;
    private final long windowMs;
    private final boolean behindProxy;
    private final int trustedProxyHops;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimitFilter(int maxRequests, long windowSeconds, boolean behindProxy, int trustedProxyHops) {
        this.maxRequests = maxRequests;
        this.windowMs = windowSeconds * 1000L;
        this.behindProxy = behindProxy;
        this.trustedProxyHops = trustedProxyHops;
    }

    private static final class Window {
        long start;
        int count;
        Window(long start) { this.start = start; }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
            chain.doFilter(req, res);
            return;
        }
        long now = System.currentTimeMillis();
        if (windows.size() > MAX_TRACKED_IPS) evictExpired(now);

        Window w = windows.computeIfAbsent(ClientIp.of(req, behindProxy, trustedProxyHops), k -> new Window(now));
        boolean limited;
        synchronized (w) {
            if (now - w.start >= windowMs) {
                w.start = now;
                w.count = 0;
            }
            limited = ++w.count > maxRequests;
        }
        if (limited) {
            res.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"Too many requests\"}");
            return;
        }
        chain.doFilter(req, res);
    }

    /**
     * Drop only entries whose window has lapsed — never the whole table, which
     * would reset every active caller's count exactly when someone floods us.
     */
    private void evictExpired(long now) {
        windows.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                return now - e.getValue().start >= windowMs;
            }
        });
    }

}
