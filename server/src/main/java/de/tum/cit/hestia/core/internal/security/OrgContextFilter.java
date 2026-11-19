package de.tum.cit.hestia.core.internal.security;

import de.tum.cit.hestia.core.OrgContext;
import de.tum.cit.hestia.core.internal.identity.HestiaPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Binds the logged-in user's organisation to the request thread ({@link OrgContext}). */
class OrgContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        UUID orgId = authentication != null && authentication.getPrincipal() instanceof HestiaPrincipal p ? p.orgId() : null;
        UUID previous = OrgContext.set(orgId);
        try {
            chain.doFilter(request, response);
        } finally {
            OrgContext.set(previous);
        }
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }
}
