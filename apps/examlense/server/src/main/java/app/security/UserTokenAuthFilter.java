package app.security;

import app.shared.DefaultUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Interim per-user authentication: a bearer token resolves to the user that owns
 * it, and the principal is that user's id. Everything downstream (the
 * {@code @CurrentUser} controller args and {@code app.shared.Access}) was
 * already written against a real owner id, so this filter is what actually turns
 * per-user isolation on.
 *
 *
 * <p>Two bootstrap credentials exist alongside real tokens, both resolving to the
 * seeded legacy user: {@code app.auth.token} (shared, committed, plain user) and
 * {@code app.auth.admin-token} (secret, no default, admin). They are separate on
 * purpose — see {@link #authenticate}.
 *
 * <p>Replaced by SAML later: the success handler will mint a {@code user_tokens}
 * row for the asserted TUM ID, so this filter keeps working unchanged and only
 * the way tokens are obtained moves.
 */
public class UserTokenAuthFilter extends OncePerRequestFilter {

    private static final List<SimpleGrantedAuthority> USER =
        List.of(new SimpleGrantedAuthority("ROLE_USER"));
    private static final List<SimpleGrantedAuthority> USER_AND_ADMIN =
        List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN"));

    private final TokenPrincipalResolver resolver;
    private final String sharedToken;
    private final String adminToken;

    public UserTokenAuthFilter(TokenPrincipalResolver resolver, String sharedToken, String adminToken) {
        this.resolver = resolver;
        this.sharedToken = sharedToken;
        // Fail closed on the easy copy-paste mistake of configuring both to the same
        // value. The shared token is committed to a public repository, so honouring
        // it as admin would publish administrator access; refusing it is the only
        // safe reading of that configuration. SecurityConfig logs it loudly.
        this.adminToken = adminToken.equals(sharedToken) ? "" : adminToken;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String presented = bearer(req);
        if (presented == null) presented = req.getParameter("token"); // EventSource/<img> can't set headers

        if (presented != null && !presented.isBlank()) {
            authenticate(presented).ifPresent(auth -> SecurityContextHolder.getContext().setAuthentication(auth));
        }
        chain.doFilter(req, res);
    }

    private Optional<UsernamePasswordAuthenticationToken> authenticate(String presented) {
        // Bootstrap admin. Checked first, and deliberately a DIFFERENT secret from
        // the shared token below: that one is committed to a public repository, so
        // granting it admin would put user administration behind a value anyone can
        // look up. This one has no default and never reaches the client bundle.
        if (!adminToken.isBlank() && constantTimeEquals(presented, adminToken)) {
            return Optional.of(legacyUser(USER_AND_ADMIN));
        }

        // Dev/bootstrap fallback: the pre-multi-user shared token, which resolves to
        // the seeded legacy user so local dev and the pre-existing exams stay
        // reachable. Plain user only — see above. Deployments switch it off by
        // setting API_AUTH_TOKEN empty (see SecurityConfig's startup warning).
        if (!sharedToken.isBlank() && constantTimeEquals(presented, sharedToken)) {
            return Optional.of(legacyUser(USER));
        }

        return resolver.resolve(presented).map(principal -> new UsernamePasswordAuthenticationToken(
            principal.userId(), null, principal.admin() ? USER_AND_ADMIN : USER));
    }

    private static UsernamePasswordAuthenticationToken legacyUser(List<SimpleGrantedAuthority> authorities) {
        return new UsernamePasswordAuthenticationToken(DefaultUser.ID.toString(), null, authorities);
    }

    /**
     * Also run on ASYNC dispatches. SSE endpoints ({@link app.sse.SseController}) put the
     * request into async mode; when Tomcat later re-dispatches it through the filter chain to
     * finalize the (already-committed) stream, Spring Security's {@code AuthorizationFilter}
     * re-evaluates {@code anyRequest().authenticated()}. A {@link OncePerRequestFilter} skips
     * async dispatches by default, so without this the principal would be missing and the
     * dispatch would fail with an {@code AccessDeniedException} on an already-committed
     * response. The token is in the same request (query param), so re-authenticating is cheap
     * — and the resolver's cache makes it free of a database hop.
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
