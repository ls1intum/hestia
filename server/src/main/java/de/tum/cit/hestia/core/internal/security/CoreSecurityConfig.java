package de.tum.cit.hestia.core.internal.security;

import static org.springframework.security.config.Customizer.withDefaults;

import de.tum.cit.hestia.core.DevAuthFilter;
import de.tum.cit.hestia.core.SecurityChainOrder;
import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * The security filter chains that belong to no module. Each module has its own chain for its URL
 * prefix ({@code /learninggoalhub/**}, {@code /workshopper/**}, {@code /examlense/**}); this class
 * adds the SAML login, which exists once and without a module prefix, and a last chain for
 * everything else (health check, error page, OpenAPI documents).
 *
 * <p>Order of the chains: {@link SecurityChainOrder#SAML_MODULE} for a module that handles its own
 * SAML login (assessment, which still issues tokens), {@link #SAML} for the shared SAML login,
 * {@link SecurityChainOrder#MODULE} for the module chains, {@link #FALLBACK} last.
 */
@Configuration
@EnableWebSecurity
class CoreSecurityConfig {

    static final int SAML = 10;
    static final int FALLBACK = 100;

    private final Environment env;

    @Autowired(required = false)
    private DevAuthFilter devAuthFilter;

    CoreSecurityConfig(Environment env) {
        this.env = env;
    }

    /** The development login must never meet production. */
    @PostConstruct
    void validateProfiles() {
        List<String> active = Arrays.asList(env.getActiveProfiles());
        if (active.contains("local") && active.contains("prod")) {
            throw new IllegalStateException(
                    "CRITICAL SECURITY ERROR: 'local' and 'prod' profiles cannot be active simultaneously.");
        }
    }

    /**
     * SAML login for materials and teaching. The clients' nginx proxies {@code /saml2/**} and
     * {@code /login/saml2/**} here with the client's prefix in X-Forwarded-Prefix, so after the login
     * the redirect to "/" leads back to the client that started it.
     */
    @Bean
    @Order(SAML)
    SecurityFilterChain coreSamlFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/saml2/**", "/login/saml2/**")
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                // Central IT fetches the service provider metadata to register us; it must stay open.
                .requestMatchers("/saml2/service-provider-metadata/**").permitAll()
                .anyRequest().authenticated())
            .saml2Login(saml2 -> saml2.defaultSuccessUrl("/", true))
            .saml2Metadata(withDefaults());
        if (devAuthFilter != null) {
            http.addFilterBefore(devAuthFilter, AnonymousAuthenticationFilter.class);
        }
        return http.build();
    }

    /**
     * The development login runs only inside the security filter chains that add it. As a
     * {@code @Component} filter Spring Boot would also register it for every request.
     */
    @Bean
    @Profile("local")
    FilterRegistrationBean<DevAuthFilter> coreDevAuthFilterRegistration(DevAuthFilter filter) {
        FilterRegistrationBean<DevAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /** Everything outside the module prefixes and the SAML login. */
    @Bean
    @Order(FALLBACK)
    SecurityFilterChain coreFallbackFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                // Container healthcheck and post-deploy smoke check
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                // Container error dispatch (an aborted SSE stream is re-dispatched here)
                .requestMatchers("/error").permitAll()
                // The OpenAPI contract is public for API consumers and client codegen. springdoc
                // only documents what the modules register.
                .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }
}
