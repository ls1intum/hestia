package app.security;

import app.shared.DefaultUser;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The static bearer token is the only thing gating the API. These tests pin the
 * accept/reject decision and the two ways a token can arrive (header for normal
 * calls, {@code ?token=} for EventSource/{@code <img>} which can't set headers).
 */
class StaticTokenAuthFilterTest {

    private static final String TOKEN = "secret-token";
    private final StaticTokenAuthFilter filter = new StaticTokenAuthFilter(TOKEN);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private Authentication runWith(MockHttpServletRequest req) throws Exception {
        filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @Test
    void validTokenInAuthorizationHeaderAuthenticatesAsDefaultUser() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + TOKEN);

        Authentication auth = runWith(req);

        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isEqualTo(DefaultUser.ID.toString());
    }

    @Test
    void validTokenInQueryParamAuthenticates() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setParameter("token", TOKEN); // EventSource / <img src> path

        assertThat(runWith(req)).isNotNull();
    }

    @Test
    void headerTakesPrecedenceOverQueryParam() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + TOKEN);
        req.setParameter("token", "wrong");

        assertThat(runWith(req)).isNotNull();
    }

    @Test
    void wrongTokenLeavesRequestUnauthenticated() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer not-the-token");

        assertThat(runWith(req)).isNull();
    }

    @Test
    void missingTokenLeavesRequestUnauthenticated() throws Exception {
        assertThat(runWith(new MockHttpServletRequest())).isNull();
    }

    @Test
    void blankConfiguredTokenNeverAuthenticates() throws Exception {
        StaticTokenAuthFilter blankFilter = new StaticTokenAuthFilter("");
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setParameter("token", "");

        blankFilter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void alwaysRunsOnAsyncDispatchSoSseReDispatchKeepsThePrincipal() {
        // SSE endpoints go async; the principal must survive the async re-dispatch
        // or Spring Security rejects the already-committed stream.
        assertThat(filter.shouldNotFilterAsyncDispatch()).isFalse();
    }

    @Test
    void chainAlwaysContinuesRegardlessOfToken() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        HttpServletRequest req = new MockHttpServletRequest();

        filter.doFilter(req, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(req); // authorization is left to the security rules
    }
}
