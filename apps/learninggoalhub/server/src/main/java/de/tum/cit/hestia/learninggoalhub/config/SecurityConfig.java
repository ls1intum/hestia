package de.tum.cit.hestia.learninggoalhub.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DevAuthFilter devAuthFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable()) // Disable CSRF temporarily so frontend isn't blocked locally
            .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
                new org.springframework.security.web.authentication.HttpStatusEntryPoint(org.springframework.http.HttpStatus.UNAUTHORIZED),
                org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.withDefaults().matcher("/api/**")
            ))
            .authorizeHttpRequests(authorize -> authorize
                // Require authentication for all APIs
                .requestMatchers("/api/**").authenticated()
                // Explicitly allow anyone to download the metadata XML (Central IT needs this!)
                .requestMatchers("/saml2/service-provider-metadata/**").permitAll()
                // Require SAML authentication for any other endpoints
                .anyRequest().authenticated()
            )
            .saml2Login(withDefaults())
            .saml2Metadata(withDefaults());

        if (devAuthFilter != null) {
            http.addFilterBefore(devAuthFilter, org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class);
        }

        return http.build();
    }
}
