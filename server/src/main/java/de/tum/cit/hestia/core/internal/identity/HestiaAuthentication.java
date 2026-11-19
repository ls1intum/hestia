package de.tum.cit.hestia.core.internal.identity;

import java.util.ArrayList;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** A login of a {@link HestiaPrincipal}: {@code ROLE_USER}, plus {@code ROLE_ADMIN} for platform admins. */
public class HestiaAuthentication extends AbstractAuthenticationToken {

    private final HestiaPrincipal principal;

    public HestiaAuthentication(HestiaPrincipal principal) {
        super(authorities(principal));
        this.principal = principal;
        setAuthenticated(true);
    }

    private static List<GrantedAuthority> authorities(HestiaPrincipal principal) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        if (principal.admin()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        }
        return authorities;
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public HestiaPrincipal getPrincipal() {
        return principal;
    }
}
