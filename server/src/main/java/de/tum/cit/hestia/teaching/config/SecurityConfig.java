package de.tum.cit.hestia.teaching.config;

import de.tum.cit.hestia.core.DevAuthFilter;
import de.tum.cit.hestia.core.SecurityChainOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * Security of the teaching module: everything under {@code /workshopper}. The SAML login itself and
 * the profile check live in core.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Autowired(required = false)
    private DevAuthFilter devAuthFilter;

    @Bean
    @Order(SecurityChainOrder.MODULE)
    public SecurityFilterChain teachingFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/workshopper/**")
            .csrf(csrf -> csrf.ignoringRequestMatchers("/workshopper/api/**"))
            .authorizeHttpRequests(authorize -> authorize
                // Health check — open to monitoring/load-balancers
                .requestMatchers(HttpMethod.GET, "/workshopper/api/workshop/health").permitAll()
                // All API paths (reads and writes) require an authenticated principal.
                // Unauthenticated callers get 401 → client redirects to /saml2/authenticate/tum.
                // The local profile's DevAuthFilter auto-authenticates for dev/test environments.
                .requestMatchers("/workshopper/api/**").authenticated()
                .anyRequest().authenticated()
            )
            .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                PathPatternRequestMatcher.withDefaults().matcher("/workshopper/api/**")
            ));

        // Register the dev bypass filter if the dev profile is active
        if (devAuthFilter != null) {
            http.addFilterBefore(devAuthFilter, UsernamePasswordAuthenticationFilter.class);
        }

        return http.build();
    }
}
