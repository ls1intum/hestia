package com.workshopper.config;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal;

public class AuthContext {
    
    /**
     * Gets the ID of the currently authenticated user.
     * In local dev where /api/** is permitAll(), this falls back to a default "system" user
     * if no authentication is present.
     */
    public static String getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return "system"; // Fallback for local development
        }
        
        Object principal = auth.getPrincipal();
        if (principal instanceof Saml2AuthenticatedPrincipal saml2Principal) {
            // e.g. Extract from TUM ID or NameID
            return saml2Principal.getName();
        }
        
        return auth.getName();
    }
}
