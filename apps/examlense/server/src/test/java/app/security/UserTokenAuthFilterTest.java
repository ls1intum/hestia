package app.security;

import app.shared.DefaultUser;
import app.security.TokenPrincipalResolver.Principal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The filter that turns a bearer token into a per-user principal — the hinge the
 * whole isolation guarantee hangs on, since every ownership check downstream
 * trusts the id it produces.
 *
 * <p>Pins: which principal each credential resolves to, that inactive
 * credentials authenticate nobody, and the two ways a token can arrive (header
 * for normal calls, {@code ?token=} for EventSource which can't set headers).
 */
class UserTokenAuthFilterTest {

    private static final String SHARED_TOKEN = "shared-bootstrap-token";
    private static final String ADMIN_TOKEN = "admin-bootstrap-token";
    private static final String USER_TOKEN = "exl_a-real-user-token";
    private static final String USER_ID = "11111111-1111-1111-1111-111111111111";

    private TokenPrincipalResolver resolver;
    private UserTokenAuthFilter filter;

    @BeforeEach
    void setUp() {
        resolver = mock(TokenPrincipalResolver.class);
        when(resolver.resolve(any())).thenReturn(Optional.empty());
        filter = new UserTokenAuthFilter(resolver, SHARED_TOKEN, ADMIN_TOKEN);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private Authentication runWith(MockHttpServletRequest req) throws Exception {
        filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
        return SecurityContextHolder.getContext().getAuthentication();
    }

    private MockHttpServletRequest bearer(String token) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + token);
        return req;
    }

    private void resolves(String token, Principal principal) {
        when(resolver.resolve(token)).thenReturn(Optional.of(principal));
    }

    @Test
    void userTokenAuthenticatesAsThatUser() throws Exception {
        resolves(USER_TOKEN, new Principal(USER_ID, false));

        Authentication auth = runWith(bearer(USER_TOKEN));

        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isEqualTo(USER_ID);
        assertThat(auth.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_USER");
    }

    @Test
    void adminFlagGrantsTheAdminRole() throws Exception {
        resolves(USER_TOKEN, new Principal(USER_ID, true));

        assertThat(runWith(bearer(USER_TOKEN)).getAuthorities())
            .extracting(Object::toString)
            .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
    }

    @Test
    void nonAdminUserDoesNotGetTheAdminRole() throws Exception {
        resolves(USER_TOKEN, new Principal(USER_ID, false));

        assertThat(runWith(bearer(USER_TOKEN)).getAuthorities())
            .extracting(Object::toString)
            .doesNotContain("ROLE_ADMIN");
    }

    @Test
    void sharedBootstrapTokenAuthenticatesAsTheLegacyUser() throws Exception {
        Authentication auth = runWith(bearer(SHARED_TOKEN));

        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isEqualTo(DefaultUser.ID.toString());
    }

    /**
     * The shared token is committed to a public repository, so it must never carry
     * admin. Admin lives behind the separate {@code app.auth.admin-token} secret and
     * behind {@code users.is_admin}.
     */
    @Test
    void sharedBootstrapTokenIsNotAdmin() throws Exception {
        assertThat(runWith(bearer(SHARED_TOKEN)).getAuthorities())
            .extracting(Object::toString)
            .containsExactly("ROLE_USER");
    }

    @Test
    void adminBootstrapTokenIsAdminAsTheLegacyUser() throws Exception {
        Authentication auth = runWith(bearer(ADMIN_TOKEN));

        assertThat(auth.getPrincipal()).isEqualTo(DefaultUser.ID.toString());
        assertThat(auth.getAuthorities())
            .extracting(Object::toString)
            .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
    }

    @Test
    void withNoAdminTokenConfiguredThereIsNoTokenRouteToAdmin() throws Exception {
        UserTokenAuthFilter noAdmin = new UserTokenAuthFilter(resolver, SHARED_TOKEN, "");

        noAdmin.doFilter(bearer(SHARED_TOKEN), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
            .extracting(Object::toString)
            .doesNotContain("ROLE_ADMIN");
    }

    /**
     * Configuring both credentials to the same value is an easy copy-paste mistake
     * with a bad outcome, since the shared value is published in a public
     * repository. The filter fails closed: the token still works as a plain user,
     * but grants no admin.
     */
    @Test
    void anAdminTokenEqualToTheSharedTokenIsNotHonouredAsAdmin() throws Exception {
        UserTokenAuthFilter misconfigured =
            new UserTokenAuthFilter(resolver, SHARED_TOKEN, SHARED_TOKEN);

        misconfigured.doFilter(bearer(SHARED_TOKEN), new MockHttpServletResponse(), new MockFilterChain());

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isEqualTo(DefaultUser.ID.toString());
        assertThat(auth.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_USER");
    }

    @Test
    void aBlankAdminTokenDoesNotMatchABlankPresentedValue() throws Exception {
        UserTokenAuthFilter noBootstrap = new UserTokenAuthFilter(resolver, "", "");
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setParameter("token", "");

        noBootstrap.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void userTokenArrivingAsQueryParamAuthenticates() throws Exception {
        resolves(USER_TOKEN, new Principal(USER_ID, false));
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setParameter("token", USER_TOKEN); // EventSource / <img src> path

        assertThat(runWith(req).getPrincipal()).isEqualTo(USER_ID);
    }

    @Test
    void headerTakesPrecedenceOverQueryParam() throws Exception {
        resolves(USER_TOKEN, new Principal(USER_ID, false));
        MockHttpServletRequest req = bearer(USER_TOKEN);
        req.setParameter("token", "wrong");

        assertThat(runWith(req).getPrincipal()).isEqualTo(USER_ID);
    }

    /** A revoked or expired credential resolves to empty, the same as an unknown one. */
    @Test
    void unresolvableTokenLeavesRequestUnauthenticated() throws Exception {
        assertThat(runWith(bearer("exl_revoked-or-unknown"))).isNull();
    }

    @Test
    void missingTokenLeavesRequestUnauthenticated() throws Exception {
        assertThat(runWith(new MockHttpServletRequest())).isNull();
    }

    @Test
    void blankSharedTokenDoesNotTurnABlankPresentedTokenIntoALogin() throws Exception {
        UserTokenAuthFilter noSharedToken = new UserTokenAuthFilter(resolver, "", "");
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setParameter("token", "");

        noSharedToken.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void withSharedTokenOffOnlyRealUserTokensWork() throws Exception {
        UserTokenAuthFilter noSharedToken = new UserTokenAuthFilter(resolver, "", "");
        resolves(USER_TOKEN, new Principal(USER_ID, false));

        noSharedToken.doFilter(bearer(SHARED_TOKEN), new MockHttpServletResponse(), new MockFilterChain());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        noSharedToken.doFilter(bearer(USER_TOKEN), new MockHttpServletResponse(), new MockFilterChain());
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(USER_ID);
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
