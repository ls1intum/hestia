package app.config;

import app.security.RateLimitFilter;
import app.security.TokenPrincipalResolver;
import app.security.UserTokenAuthFilter;
import app.security.Saml2AuthenticationSuccessHandler;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
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

import static org.springframework.security.config.Customizer.withDefaults;

/**
 * Stateless API security with interim per-user token authentication.
 *
 * <p>A bearer token resolves to the user that owns it, and the principal is that
 * user's id — which is what makes the ownership checks already present in the
 * controllers isolate real people. Registration is open: a first-time visitor
 * gets an account and a token from {@code POST /api/auth/register}. TUM SAML
 * replaces that step later without touching the API chain.
 *
 * <p>Two chains on purpose. The API chain is {@code STATELESS}, which is correct for
 * bearer-token traffic but makes a SAML handshake impossible — the authn request's
 * relay state has nowhere to live. So {@code /saml2/**} and {@code /login/saml2/**}
 * get their own chain that permits a session.
 *
 * <p>Cross-user surfaces ({@code /api/parse-metrics}, {@code /api/admin/**})
 * require {@code ROLE_ADMIN}, which comes from {@code users.is_admin} or from the
 * separate {@code app.auth.admin-token} bootstrap secret.
 *
 * <p>Public endpoints: {@code /api/healthz} (liveness probe), CORS preflight, the
 * signature-gated file endpoint, {@code /api/auth/register} (how a caller gets
 * their first credential), SP metadata, and — only when {@code app.docs.enabled}
 * is set — the OpenAPI spec and Swagger UI.
 */
@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    /**
     * Pre-multi-user shared token. Kept as a bootstrap path: it resolves to the
     * seeded legacy user so local dev and the existing tests keep working, and so
     * the pre-existing exams stay reachable. Deployments must set
     * it empty once real users are enrolled — until then it is a shared identity.
     */
    @Value("${app.auth.token}")
    private String authToken;

    /**
     * Separate bootstrap credential that grants admin. Deliberately NOT
     * {@code app.auth.token}: that value is committed to a public repository and
     * ships in dev config, so putting user administration behind it would mean
     * anyone who reads the repo could promote themselves and revoke access. No default —
     * unset means no token-based admin at all, and admin comes only from
     * {@code users.is_admin}.
     */
    @Value("${app.auth.admin-token:}")
    private String adminToken;

    @Value("${app.ratelimit.requests:300}")
    private int rateLimitRequests;

    @Value("${app.ratelimit.window-seconds:10}")
    private long rateLimitWindowSeconds;

    /** Only trust X-Forwarded-For when we're actually deployed behind a reverse proxy. */
    @Value("${app.ratelimit.behind-proxy:false}")
    private boolean behindProxy;

    /** How many entries our own proxies append; see {@link app.security.ClientIp}. */
    @Value("${app.ratelimit.trusted-proxy-hops:2}")
    private int trustedProxyHops;

    /**
     * Whether to open the OpenAPI spec / Swagger UI paths. Same switch that
     * enables springdoc itself (see application.yml), so the two cannot drift.
     * With docs off these fall through to {@code anyRequest().authenticated()}
     * and answer 401 — the filter chain runs before the servlet would 404, so
     * the spec's absence is not distinguishable from any other protected path.
     *
     * Swagger UI needs them unauthenticated when they are on: the browser page
     * cannot present the bearer token on its initial load.
     */
    @Value("${app.docs.enabled:false}")
    private boolean docsEnabled;

    private final TokenPrincipalResolver principalResolver;

    public SecurityConfig(TokenPrincipalResolver principalResolver) {
        this.principalResolver = principalResolver;
    }

    @PostConstruct
    void warnOnBootstrapCredentials() {
        if (!authToken.isBlank()) {
            log.warn("app.auth.token is set: every request presenting it authenticates as the "
                + "SHARED legacy user, bypassing per-user isolation. Intended for local dev and "
                + "for reaching pre-existing exams. Set API_AUTH_TOKEN empty once users are enrolled.");
        }
        if (!adminToken.isBlank()) {
            if (adminToken.equals(authToken)) {
                log.error("app.auth.admin-token equals app.auth.token and is being IGNORED. The "
                    + "shared token is committed to a public repository, so honouring it would "
                    + "grant admin to anyone who reads it. Set ADMIN_BOOTSTRAP_TOKEN to a distinct "
                    + "secret value.");
            } else {
                log.warn("app.auth.admin-token is set: it grants ADMIN as the legacy user. Use it "
                    + "to enrol and promote your real account, then unset ADMIN_BOOTSTRAP_TOKEN.");
            }
        }
    }


    /**
     * SAML handshake only. Separate from the API chain because {@code saml2Login}
     * needs a session to hold the in-flight authn request, and the API chain is
     * deliberately stateless.
     */
    @Autowired(required = false)
    private DevAuthFilter devAuthFilter;

    @Bean
    @Order(1)
    public SecurityFilterChain samlFilterChain(HttpSecurity http, Saml2AuthenticationSuccessHandler successHandler) throws Exception {
        http
            .securityMatcher("/saml2/**", "/login/saml2/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .authorizeHttpRequests(auth -> auth
                // Central IT fetches this to register us; it must stay open.
                .requestMatchers("/saml2/service-provider-metadata/**").permitAll()
                .anyRequest().authenticated())
            .saml2Login(saml2 -> saml2.successHandler(successHandler))
            .saml2Metadata(withDefaults());

        if (devAuthFilter != null) {
            http.addFilterBefore(devAuthFilter, UsernamePasswordAuthenticationFilter.class);
        }

        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        UserTokenAuthFilter tokenFilter = new UserTokenAuthFilter(principalResolver, authToken, adminToken);
        RateLimitFilter rateLimitFilter = new RateLimitFilter(
            rateLimitRequests, rateLimitWindowSeconds, behindProxy, trustedProxyHops);

        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> {
                auth
                    .requestMatchers("/api/healthz").permitAll()
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    .requestMatchers("/api/files/**").permitAll() // signed-URL file content (validated by HMAC in 2b)
                    // How a first-time visitor obtains their first credential, so it
                    // cannot require one. Abuse is bounded by the per-IP registration
                    // cap in UserService plus the request limiter in front of it.
                    .requestMatchers(HttpMethod.POST, "/api/auth/register").permitAll()
                    // Container-generated error dispatch. An SSE stream that ends
                    // abnormally (client disconnect) is re-dispatched to /error after
                    // the response is already committed; without permitting it here the
                    // AuthorizationFilter denies (no principal on the error dispatch),
                    // and ExceptionTranslationFilter logs a noisy "response already
                    // committed" ServletException. The real endpoints stay authenticated.
                    .requestMatchers("/error").permitAll();
                if (docsEnabled) {
                    auth.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll();
                }
                // Cross-user surfaces. Everything else is scoped to the caller's own
                // rows by the controllers, but these two aggregate or administer
                // across users, so they need the role rather than just a principal.
                auth.requestMatchers("/api/parse-metrics").hasRole("ADMIN");
                auth.requestMatchers("/api/admin/**").hasRole("ADMIN");
                auth.anyRequest().authenticated();
            })
            // 401 (not the default 403) when no/invalid token is presented.
            .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> {
                res.setStatus(401);
                res.setContentType("application/json");
                res.getWriter().write("{\"error\":\"Unauthorized\"}");
            }))
            // token auth before the username/password filter; rate limit before that.
            .addFilterBefore(tokenFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(rateLimitFilter, UserTokenAuthFilter.class);
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
