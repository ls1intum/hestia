package com.workshopper.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable()) // Disable CSRF temporarily so your frontend isn't blocked locally
            .authorizeHttpRequests(authorize -> authorize
                // Keep all existing API endpoints open as requested
                .requestMatchers("/api/**").permitAll()
                // Explicitly allow anyone to download the metadata XML (Central IT needs this!)
                .requestMatchers("/saml2/service-provider-metadata/**").permitAll()
                // Require SAML authentication for any other endpoints
                .anyRequest().authenticated()
            )
            .saml2Login(withDefaults())
            .saml2Metadata(withDefaults());

        return http.build();
    }
}
