package app.user;

import app.error.ApiException;
import app.security.CurrentUser;
import app.user.UserDtos.LinkTumIdRequest;
import app.user.UserDtos.MeResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@Tag(name = "Identity", description = "Who the caller is, and how much LLM quota they have left.")
public class MeController {

    private final UserRepository users;
    private final UserService userService;
    private final LlmQuotaService quota;

    public MeController(UserRepository users, UserService userService, LlmQuotaService quota) {
        this.users = users;
        this.userService = userService;
        this.quota = quota;
    }

    @Operation(
        summary = "The authenticated user",
        description = """
            Doubles as the token-validity probe the client uses before entering the app, \
            and supplies the remaining quota so the UI can warn before a run is spent.

            `is_admin` reflects the caller's *effective* authority, not just the database \
            column — the bootstrap admin token grants admin without one.""")
    @GetMapping("/me")
    public MeResponse me(@CurrentUser String userId, Authentication authentication) {
        User user = users.findById(UUID.fromString(userId))
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Unknown user"));
        return MeResponse.of(user, isAdmin(authentication), quota.remainingFor(userId));
    }

    @Operation(
        summary = "Link a TUM ID to this account",
        description = """
            Replaces the account's generated `anon-…` handle with the caller's TUM ID. \
            Optional, but it is what carries the account's exams across the SAML \
            cutover — an unlinked account has nothing for a TUM login to match on.""")
    @ApiResponse(responseCode = "409", description = "That TUM ID is already linked to another account.")
    @PatchMapping("/me")
    public MeResponse linkTumId(
        @CurrentUser String userId,
        @RequestBody LinkTumIdRequest body,
        Authentication authentication
    ) {
        User updated = userService.linkExternalId(UUID.fromString(userId), body.external_id());
        return MeResponse.of(updated, isAdmin(authentication), quota.remainingFor(userId));
    }

    /**
     * Read admin off the granted authorities rather than {@code users.is_admin}.
     * The two can legitimately differ: the bootstrap token grants admin as the
     * legacy user without setting the column. Reporting the column instead would
     * leave the UI offering an admin area the server then refuses, or hiding one
     * the caller is entitled to.
     */
    private static boolean isAdmin(Authentication authentication) {
        return authentication != null
            && authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_ADMIN"::equals);
    }
}
