package de.tum.cit.hestia.materials.config;

import de.tum.cit.hestia.core.DevAuthFilter;
import de.tum.cit.hestia.core.SecurityChainOrder;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
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

/**
 * The security chain of the materials module: every request under {@code /learninggoalhub/**} needs
 * a login (the shared SAML login of core, or the development login), and an unauthenticated request
 * gets a 401. Health check, OpenAPI documents and the SAML endpoints belong to core's chains.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Autowired(required = false)
    private DevAuthFilter devAuthFilter;

    private static final PathPatternRequestMatcher.Builder PATHS = PathPatternRequestMatcher.withDefaults();

    /** Requests that start model calls (upload included: the bookmark judge reads the outline). */
    static final RequestMatcher LLM_REQUESTS = new OrRequestMatcher(
            PATHS.matcher(HttpMethod.POST, "/learninggoalhub/api/courses/*/extract"),
            PATHS.matcher(HttpMethod.POST, "/learninggoalhub/api/courses/*/competency-tree"),
            PATHS.matcher(HttpMethod.POST, "/learninggoalhub/api/courses/*/documents"),
            PATHS.matcher(HttpMethod.POST, "/learninggoalhub/api/courses/*/exam-tasks/learning-goals"),
            PATHS.matcher(HttpMethod.POST, "/learninggoalhub/api/courses/*/learning-goals/topic-search/**"),
            PATHS.matcher(HttpMethod.POST, "/learninggoalhub/api/courses/*/learning-goals/terminal/generated"));

    @Bean
    @Order(SecurityChainOrder.MODULE)
    public SecurityFilterChain materialsFilterChain(
            HttpSecurity http,
            @Value("${hestia.rate-limit.llm-requests:60}") int llmRequestLimit,
            @Value("${hestia.rate-limit.window:10m}") Duration llmRequestWindow) throws Exception {
        LlmRateLimitFilter rateLimit = new LlmRateLimitFilter(
                LLM_REQUESTS, llmRequestLimit, llmRequestWindow, Clock.systemUTC());

        http
            .securityMatcher("/learninggoalhub/**")
            // CSRF still off: turning it on needs the client to echo a token (docs/SECURITY.md).
            .csrf(csrf -> csrf.disable())
            .addFilterBefore(rateLimit, AnonymousAuthenticationFilter.class);

        if (devAuthFilter != null) {
            http.addFilterBefore(devAuthFilter, AnonymousAuthenticationFilter.class);
        }

        http
            // An API client gets a 401, not a redirect to the SAML login page.
            .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated());

        return http.build();
    }
}
