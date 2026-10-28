package de.tum.cit.hestia.learninggoalhub.config;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final PathPatternRequestMatcher.Builder PATHS = PathPatternRequestMatcher.withDefaults();

    /** Endpoints only the other apps' servers call; the client never does. */
    static final RequestMatcher SERVICE_ONLY = new OrRequestMatcher(
            PATHS.matcher(HttpMethod.POST, "/api/courses/*/exam-tasks/learning-goals"),
            PATHS.matcher(HttpMethod.GET, "/api/courses/*/learning-goals/by-session"));

    /** Requests that start model calls (upload included: the bookmark judge reads the outline). */
    static final RequestMatcher LLM_REQUESTS = new OrRequestMatcher(
            PATHS.matcher(HttpMethod.POST, "/api/courses/*/extract"),
            PATHS.matcher(HttpMethod.POST, "/api/courses/*/competency-tree"),
            PATHS.matcher(HttpMethod.POST, "/api/courses/*/documents"),
            PATHS.matcher(HttpMethod.POST, "/api/courses/*/exam-tasks/learning-goals"),
            PATHS.matcher(HttpMethod.POST, "/api/courses/*/learning-goals/topic-search/**"),
            PATHS.matcher(HttpMethod.POST, "/api/courses/*/learning-goals/terminal/generated"));

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            @Value("${hestia.security.api-key.examlense:}") String examlenseKey,
            @Value("${hestia.security.api-key.workshopper:}") String workshopperKey,
            // Off until the consumers send their key; until then rejections are only logged.
            @Value("${hestia.security.enforce-service-keys:false}") boolean enforceServiceKeys,
            @Value("${hestia.rate-limit.llm-requests:60}") int llmRequestLimit,
            @Value("${hestia.rate-limit.window:10m}") Duration llmRequestWindow) throws Exception {
        ServiceApiKeyFilter serviceKeys = new ServiceApiKeyFilter(
                Map.of("examlense", examlenseKey, "workshopper", workshopperKey),
                SERVICE_ONLY, enforceServiceKeys);
        LlmRateLimitFilter rateLimit = new LlmRateLimitFilter(
                LLM_REQUESTS, llmRequestLimit, llmRequestWindow, Clock.systemUTC());

        http
            // CSRF off: /api/** has no login yet, so there is no session to forge requests with.
            // Turn it on together with the SAML login (#116).
            .csrf(csrf -> csrf.disable())
            .addFilterBefore(serviceKeys, AnonymousAuthenticationFilter.class)
            .addFilterAfter(rateLimit, ServiceApiKeyFilter.class)
            // An API client gets a 401, not a redirect to the SAML login page.
            .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), PATHS.matcher("/api/**")))
            .authorizeHttpRequests(authorize -> {
                if (enforceServiceKeys) {
                    authorize.requestMatchers(SERVICE_ONLY).hasRole(ServiceApiKeyFilter.ROLE);
                }
                authorize
                    // Keep all existing API endpoints open for now
                    .requestMatchers("/api/**").permitAll()
                    // The OpenAPI contract is public for API consumers and client codegen (docs/api.md)
                    .requestMatchers("/v3/api-docs", "/v3/api-docs/**").permitAll()
                    // Container healthcheck and post-deploy smoke check
                    .requestMatchers("/actuator/health").permitAll()
                    // Explicitly allow anyone to download the metadata XML (Central IT needs this!)
                    .requestMatchers("/saml2/service-provider-metadata/**").permitAll()
                    // Require SAML authentication for any other endpoints
                    .anyRequest().authenticated();
            })
            .saml2Login(withDefaults())
            .saml2Metadata(withDefaults());

        return http.build();
    }
}
