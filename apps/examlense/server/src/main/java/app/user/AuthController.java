package app.user;

import app.security.ClientIp;
import app.user.UserDtos.MeResponse;
import app.user.UserDtos.RegisterResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Registration", description = """
    Open self-registration, in place until TUM SAML is live. A first-time visitor \
    gets their own account and their own private set of exams without asking anyone \
    for access.""")
public class AuthController {

    private final UserService users;
    private final LlmQuotaService quota;

    /** Only trust X-Forwarded-For when actually deployed behind a reverse proxy. */
    @Value("${app.ratelimit.behind-proxy:false}")
    private boolean behindProxy;

    /** How many entries our own proxies append; see {@link ClientIp}. */
    @Value("${app.ratelimit.trusted-proxy-hops:2}")
    private int trustedProxyHops;

    public AuthController(UserService users, LlmQuotaService quota) {
        this.users = users;
        this.quota = quota;
    }

    @Operation(
        summary = "Register a new account",
        description = """
            Creates an account and returns its session token. **Unauthenticated by \
            design** — this is how a first-time visitor gets their first credential, \
            and the client calls it automatically when it holds no token.

            The account starts with a generated `anon-…` handle and no TUM ID; link \
            one later via `PATCH /api/me` so it survives the SAML cutover.

            Capped per IP per day (`app.auth.registrations-per-ip-per-day`): without \
            that, clearing browser storage would mint an account with a fresh LLM \
            quota, making per-user metering meaningless.""")
    @ApiResponse(responseCode = "200", description = "Registered; the response carries the session token.")
    @ApiResponse(responseCode = "403", description = "Open registration is disabled on this instance.")
    @ApiResponse(responseCode = "429", description = "This network has registered too many accounts today.")
    @PostMapping("/register")
    public RegisterResponse register(HttpServletRequest request) {
        UserService.Registration created =
            users.register(Tokens.hash(ClientIp.of(request, behindProxy, trustedProxyHops)));
        User user = created.user();
        return new RegisterResponse(
            created.token(),
            // Freshly registered accounts are never admin.
            MeResponse.of(user, false, quota.remainingFor(user.getId().toString())));
    }
}
