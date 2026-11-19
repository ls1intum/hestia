package de.tum.cit.hestia.core.internal.security;

import de.tum.cit.hestia.core.internal.identity.HestiaAuthentication;
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
 * counts as {@value #DEFAULT_USER} of organisation {@value #DEFAULT_ORG}. The headers
 * {@value #USER_HEADER} and {@value #ORG_HEADER} select another user or organisation, which is how
 * tests show that another university sees nothing. The login is not stored in the session.
 */
class DevAuthFilter extends OncePerRequestFilter {

    static final String USER_HEADER = "X-Dev-User";
    static final String ORG_HEADER = "X-Dev-Org";
    static final String DEFAULT_USER = "dev-local-user";
    static final String DEFAULT_ORG = "local.test";

    private final IdentityService identities;

    DevAuthFilter(IdentityService identities) {
        this.identities = identities;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var context = SecurityContextHolder.getContext();
        if (context.getAuthentication() == null || !context.getAuthentication().isAuthenticated()) {
            String user = header(request, USER_HEADER, DEFAULT_USER);
            String org = header(request, ORG_HEADER, DEFAULT_ORG);
            context.setAuthentication(new HestiaAuthentication(identities.login(org, user, user)));
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
