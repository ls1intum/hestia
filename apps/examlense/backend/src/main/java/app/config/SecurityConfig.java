package app.config;

import app.security.RateLimitFilter;
import app.security.StaticTokenAuthFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Stateless API security. Real user authentication is deferred to a later phase;
 * for now the API is gated by a single static bearer token plus a coarse per-IP
 * rate limiter (bot/overload protection only). A valid token authenticates the
 * request as the single seeded user (see {@link app.shared.DefaultUser}).
 *
 * Public endpoints: /api/healthz (liveness probe) and CORS preflight.
 */
@Configuration
public class SecurityConfig {

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    @Value("${app.auth.token}")
    private String authToken;

    @Value("${app.ratelimit.requests:300}")
    private int rateLimitRequests;

    @Value("${app.ratelimit.window-seconds:10}")
    private long rateLimitWindowSeconds;

    /** Only trust X-Forwarded-For when we're actually deployed behind a reverse proxy. */
    @Value("${app.ratelimit.behind-proxy:false}")
    private boolean behindProxy;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        StaticTokenAuthFilter tokenFilter = new StaticTokenAuthFilter(authToken);
        RateLimitFilter rateLimitFilter = new RateLimitFilter(rateLimitRequests, rateLimitWindowSeconds, behindProxy);

        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/healthz").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/api/files/**").permitAll() // signed-URL file content (validated by HMAC in 2b)
                // Container-generated error dispatch. An SSE stream that ends
                // abnormally (client disconnect) is re-dispatched to /error after
                // the response is already committed; without permitting it here the
                // AuthorizationFilter denies (no principal on the error dispatch),
                // and ExceptionTranslationFilter logs a noisy "response already
                // committed" ServletException. The real endpoints stay authenticated.
                .requestMatchers("/error").permitAll()
                .anyRequest().authenticated()
            )
            // 401 (not the default 403) when no/invalid token is presented.
            .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> {
                res.setStatus(401);
                res.setContentType("application/json");
                res.getWriter().write("{\"error\":\"Unauthorized\"}");
            }))
            // token auth before the username/password filter; rate limit before that.
            .addFilterBefore(tokenFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(rateLimitFilter, StaticTokenAuthFilter.class);
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(allowedOrigins.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Content-Type", "Authorization"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
