package de.tum.cit.hestia.assessment.config;

import de.tum.cit.hestia.assessment.user.User;
import de.tum.cit.hestia.assessment.user.UserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Bypasses SAML authentication when the "local" profile is active.
 * Intercepts the login redirect and immediately mints a valid token for a mock developer user.
 */
@Component
@Profile("local")
public class DevAuthFilter extends OncePerRequestFilter {

    private final UserService userService;

    public DevAuthFilter(UserService userService) {
        this.userService = userService;
    }

    /**
     * Only for the ExamLense client: its nginx sends X-Forwarded-Prefix /examlense, which becomes
     * the context path. Requests of the other clients are left to the shared development login.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"/examlense".equals(request.getContextPath());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (request.getRequestURI().contains("/saml2/authenticate/tum")) {
            // Create a mock user
            User user = userService.findOrCreateByExternalId("dev-local-user", "Dev User");
            
            // Mint a token for the React app
            String token = userService.mintToken(user.getId(), "Local Dev Bypass");

            // Redirect back to the frontend with the token
            String contextPath = request.getContextPath() != null ? request.getContextPath() : "";
            response.sendRedirect(contextPath + "/#token=" + token);
            return;
        }

        filterChain.doFilter(request, response);
    }
}
