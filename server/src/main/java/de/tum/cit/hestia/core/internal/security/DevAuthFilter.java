package de.tum.cit.hestia.core.internal.security;

import de.tum.cit.hestia.core.internal.identity.HestiaAuthentication;
import de.tum.cit.hestia.core.internal.identity.HestiaPrincipal;
import de.tum.cit.hestia.core.internal.identity.IdentityService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Local development login (profile {@code local} only, also on Staging): a request without a login
 * counts as {@value #DEFAULT_USER} of organisation {@value #DEFAULT_ORG}. It never grants the
 * platform admin role, whatever {@code hestia.security.platform-admins} says.
 *
 * <p>Only with {@code hestia.dev-login.impersonation=true} do the headers {@value #USER_HEADER} and
 * {@value #ORG_HEADER} select another user or organisation, which is how tests show that another
 * university sees nothing. A deployed server refuses to start with it ({@link DeployGuard}). The
 * login is not stored in the session.
 */
class DevAuthFilter extends OncePerRequestFilter {

    static final String USER_HEADER = "X-Dev-User";
    static final String ORG_HEADER = "X-Dev-Org";
    static final String DEFAULT_USER = "dev-local-user";
    static final String DEFAULT_ORG = "local.test";

    private final IdentityService identities;
    private final boolean impersonation;

    DevAuthFilter(IdentityService identities, boolean impersonation) {
        this.identities = identities;
        this.impersonation = impersonation;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var context = SecurityContextHolder.getContext();
        if (context.getAuthentication() == null || !context.getAuthentication().isAuthenticated()) {
            String user = impersonation ? header(request, USER_HEADER, DEFAULT_USER) : DEFAULT_USER;
            String org = impersonation ? header(request, ORG_HEADER, DEFAULT_ORG) : DEFAULT_ORG;
            HestiaPrincipal login = identities.login(org, user, user);
            HestiaPrincipal withoutAdmin = new HestiaPrincipal(login.userId(), login.orgId(), login.externalId(),
                    login.displayName(), false, login.email());
            context.setAuthentication(new HestiaAuthentication(withoutAdmin));
        }
        chain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    private static String header(HttpServletRequest request, String name, String fallback) {
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
