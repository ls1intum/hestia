package app.config;

import app.user.User;
import app.user.UserService;
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

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (request.getRequestURI().contains("/saml2/authenticate/tum")) {
            // Create a mock user
            User user = userService.findOrCreateByExternalId("dev-local-user", "Dev User");
            
            // Mint a token for the React app
            String token = userService.mintToken(user.getId(), "Local Dev Bypass");

            // Redirect back to the frontend with the token
            response.sendRedirect("/#token=" + token);
            return;
        }

        filterChain.doFilter(request, response);
    }
}
