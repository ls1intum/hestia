package com.workshopper.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Autowired
    private Environment env;

    @Autowired(required = false)
    private DevAuthFilter devAuthFilter;

    @PostConstruct
    public void validateProfiles() {
        boolean isLocal = Arrays.asList(env.getActiveProfiles()).contains("local");
        boolean isProd = Arrays.asList(env.getActiveProfiles()).contains("prod");
        if (isLocal) {
            if (isProd) {
                throw new IllegalStateException("CRITICAL SECURITY ERROR: 'local' and 'prod' profiles cannot be active simultaneously.");
            }
            // HARD GATE: Catch misconfigured environments where 'local' is active alone in a deployed environment
            if (System.getenv("KUBERNETES_SERVICE_HOST") != null || System.getenv("FLY_APP_NAME") != null) {
                throw new IllegalStateException("CRITICAL SECURITY ERROR: 'local' profile is active in a deployed environment!");
            }
        }
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
            .authorizeHttpRequests(authorize -> authorize
                // Health check — open to monitoring/load-balancers
                .requestMatchers(HttpMethod.GET, "/api/workshop/health").permitAll()
                // SAML metadata — open (Central IT needs this to register the SP)
                .requestMatchers("/saml2/service-provider-metadata/**").permitAll()
                // All API paths (reads and writes) require an authenticated principal.
                // Unauthenticated callers get 401 → frontend redirects to /saml2/authenticate/tum.
                // The local profile's DevAuthFilter auto-authenticates for dev/test environments.
                .requestMatchers("/api/**").authenticated()
                // Everything else (SAML login, actuator, etc.) also requires auth
                .anyRequest().authenticated()
            )

            .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
                new org.springframework.security.web.authentication.HttpStatusEntryPoint(org.springframework.http.HttpStatus.UNAUTHORIZED),
                org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.withDefaults().matcher("/api/**")
            ))
            .saml2Login(withDefaults())

            .saml2Metadata(withDefaults());

        // Register the dev bypass filter if the dev profile is active
        if (devAuthFilter != null) {
            http.addFilterBefore(devAuthFilter, UsernamePasswordAuthenticationFilter.class);
        }

        return http.build();
    }
}
