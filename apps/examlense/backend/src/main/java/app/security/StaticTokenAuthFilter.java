package app.security;

import app.persistence.DefaultUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Minimal bot/overload protection — NOT real authentication (deferred to a later
 * phase). A single shared bearer token gates the API; on match we set a
 * single-user principal ({@link DefaultUser#ID}). Requests without a valid token
 * stay unauthenticated and are rejected by the authorization rules.
 */
public class StaticTokenAuthFilter extends OncePerRequestFilter {

    private static final String PRINCIPAL = DefaultUser.ID.toString();
    private final String token;

    public StaticTokenAuthFilter(String token) {
        this.token = token;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String presented = bearer(req);
        if (presented == null) presented = req.getParameter("token"); // EventSource/<img> can't set headers
        if (presented != null && !token.isBlank() && constantTimeEquals(presented, token)) {
            var auth = new UsernamePasswordAuthenticationToken(PRINCIPAL, null, List.of());
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        chain.doFilter(req, res);
    }

    /**
     * Also run on ASYNC dispatches. SSE endpoints ({@link app.sse.SseController}) put the
     * request into async mode; when Tomcat later re-dispatches it through the filter chain to
     * finalize the (already-committed) stream, Spring Security's {@code AuthorizationFilter}
     * re-evaluates {@code anyRequest().authenticated()}. A {@link OncePerRequestFilter} skips
     * async dispatches by default, so without this the principal would be missing and the
     * dispatch would fail with an {@code AccessDeniedException} on an already-committed
     * response. The token is in the same request (query param), so re-authenticating is cheap.
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    private static String bearer(HttpServletRequest req) {
        String header = req.getHeader("Authorization");
        return (header != null && header.startsWith("Bearer ")) ? header.substring(7).trim() : null;
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
