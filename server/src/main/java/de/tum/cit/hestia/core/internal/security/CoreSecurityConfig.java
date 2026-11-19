package de.tum.cit.hestia.core.internal.security;

import static org.springframework.security.config.Customizer.withDefaults;

import de.tum.cit.hestia.core.internal.identity.IdentityService;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The one security filter chain of the server, session based. One SAML login (registration
 * {@code tum}) without module prefix; the clients' nginx proxies {@code /saml2/**} and
 * {@code /login/saml2/**} with the client's prefix in X-Forwarded-Prefix, so after the login the
 * redirect to "/" leads back to the client that started it.
 *
 * <p>Open: health endpoints, the service provider metadata, the error page and the OpenAPI
 * documents (if enabled). Admin only: ExamLense's admin and parse-metrics endpoints. Everything
 * else needs a login; without one the answer is 401, never a redirect. CSRF protection is on: the
 * clients echo the {@code XSRF-TOKEN} cookie in the {@code X-XSRF-TOKEN} header.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class CoreSecurityConfig {

    private static final PathPatternRequestMatcher.Builder PATHS = PathPatternRequestMatcher.withDefaults();

    /** Requests of the materials module that start model calls (upload included: the bookmark judge reads the outline). */
    static final RequestMatcher LLM_REQUESTS = new OrRequestMatcher(
            PATHS.matcher(HttpMethod.POST, "/learninggoalhub/api/courses/*/extract"),
            PATHS.matcher(HttpMethod.POST, "/learninggoalhub/api/courses/*/competency-tree"),
            PATHS.matcher(HttpMethod.POST, "/learninggoalhub/api/courses/*/documents"),
            PATHS.matcher(HttpMethod.POST, "/learninggoalhub/api/courses/*/exam-tasks/learning-goals"),
            PATHS.matcher(HttpMethod.POST, "/learninggoalhub/api/courses/*/learning-goals/topic-search/**"),
            PATHS.matcher(HttpMethod.POST, "/learninggoalhub/api/courses/*/learning-goals/terminal/generated"));

    /** The assessment module (ExamLense) has its own per-address request limit. */
    static final RequestMatcher ASSESSMENT = PATHS.matcher("/examlense/**");

    private final Environment env;

    CoreSecurityConfig(Environment env) {
        this.env = env;
    }

    /** The development login must never meet production. */
    @PostConstruct
    void validateProfiles() {
        List<String> active = Arrays.asList(env.getActiveProfiles());
        if (!active.contains("local")) {
            return;
        }
        if (active.contains("prod")) {
            throw new IllegalStateException(
                    "CRITICAL SECURITY ERROR: 'local' and 'prod' profiles cannot be active simultaneously.");
        }
        // Catch a deployed environment where 'local' is active alone
        if (System.getenv("KUBERNETES_SERVICE_HOST") != null || System.getenv("FLY_APP_NAME") != null) {
            throw new IllegalStateException(
                    "CRITICAL SECURITY ERROR: 'local' profile is active in a deployed environment!");
        }
    }

    @Bean
    SecurityFilterChain hestiaFilterChain(
            HttpSecurity http,
            IdentityService identities,
            @Value("${hestia.rate-limit.llm-requests:60}") int llmRequestLimit,
            @Value("${hestia.rate-limit.window:10m}") Duration llmRequestWindow,
            @Value("${app.ratelimit.requests:300}") int assessmentRequestLimit,
            @Value("${app.ratelimit.window-seconds:10}") long assessmentWindowSeconds,
            @Value("${app.ratelimit.behind-proxy:false}") boolean behindProxy,
            @Value("${app.ratelimit.trusted-proxy-hops:2}") int trustedProxyHops,
            @Value("${app.cors.allowed-origins:}") String assessmentCorsOrigins) throws Exception {

        LlmRateLimitFilter llmRateLimit = new LlmRateLimitFilter(
                LLM_REQUESTS, llmRequestLimit, llmRequestWindow, Clock.systemUTC());
        OncePerRequestFilter assessmentRateLimit = only(ASSESSMENT, new RateLimitFilter(
                assessmentRequestLimit, assessmentWindowSeconds, behindProxy, trustedProxyHops));

        http
            .cors(cors -> cors.configurationSource(assessmentCors(assessmentCorsOrigins)))
            .csrf(csrf -> csrf
                .csrfTokenRepository(SpaCsrf.repository())
                .csrfTokenRequestHandler(new SpaCsrf.RequestHandler()))
            .addFilterAfter(new SpaCsrf.CookieFilter(), BasicAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                // Container healthcheck, post-deploy smoke check, the modules' own health endpoints
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/examlense/api/healthz", "/workshopper/api/workshop/health").permitAll()
                // Central IT fetches the service provider metadata to register us; it must stay open.
                .requestMatchers("/saml2/service-provider-metadata/**").permitAll()
                // Container error dispatch (an aborted SSE stream is re-dispatched here)
                .requestMatchers("/error").permitAll()
                // The OpenAPI contract, where springdoc is enabled; it documents only what the modules register.
                .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                // CORS preflight of the ExamLense client in development
                .requestMatchers(HttpMethod.OPTIONS, "/examlense/**").permitAll()
                // Cross-user surfaces of ExamLense: platform admins only
                .requestMatchers("/examlense/api/admin/**", "/examlense/api/parse-metrics").hasRole("ADMIN")
                .anyRequest().authenticated())
            .saml2Login(saml2 -> saml2
                .authenticationManager(new ProviderManager(new SamlLogin(identities)))
                .defaultSuccessUrl("/", true)
                .failureHandler((request, response, e) ->
                        response.sendError(HttpStatus.FORBIDDEN.value(), "Login rejected")))
            .saml2Metadata(withDefaults())
            .logout(logout -> logout
                .logoutRequestMatcher(PATHS.matcher(HttpMethod.POST, "/*/api/logout"))
                .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
            .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .addFilterBefore(llmRateLimit, AnonymousAuthenticationFilter.class)
            .addFilterBefore(assessmentRateLimit, AnonymousAuthenticationFilter.class)
            .addFilterAfter(new OrgContextFilter(), AnonymousAuthenticationFilter.class);

        if (env.matchesProfiles("local")) {
            http.addFilterBefore(new DevAuthFilter(identities), AnonymousAuthenticationFilter.class);
        }
        return http.build();
    }

    /** ExamLense's development client may call from another origin; no other module allows that. */
    private static UrlBasedCorsConfigurationSource assessmentCors(String allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Content-Type"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/examlense/**", config);
        return source;
    }

    /** Applies {@code filter} only to requests that {@code matcher} matches. */
    private static OncePerRequestFilter only(RequestMatcher matcher, OncePerRequestFilter filter) {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                if (matcher.matches(request)) {
                    filter.doFilter(request, response, chain);
                } else {
                    chain.doFilter(request, response);
                }
            }
        };
    }
}
