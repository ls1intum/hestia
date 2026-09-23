package app.security;

import app.user.User;
import app.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class Saml2AuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private final UserService userService;

    public Saml2AuthenticationSuccessHandler(UserService userService) {
        this.userService = userService;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException {

        Saml2AuthenticatedPrincipal principal = (Saml2AuthenticatedPrincipal) authentication.getPrincipal();
        
        // Look for the standard eduPersonPrincipalName, or fallback to NameID
        String eppn = principal.getFirstAttribute("urn:oid:1.3.6.1.4.1.5923.1.1.1.6");
        if (eppn == null || eppn.isBlank()) {
            eppn = principal.getName();
        }

        // The exact assertion format is tricky, so UserService.normalizeExternalId will parse it
        User user = userService.findOrCreateByExternalId(eppn, eppn);
        
        // Mint a session token for the React app
        String token = userService.mintToken(user.getId(), "SAML Login");

        // Redirect back to the React app with the token in the URL fragment.
        // Spring Boot's forward-headers-strategy will automatically prepend the /examlense prefix.
        response.sendRedirect("/#token=" + token);
    }
}
