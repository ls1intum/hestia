package app.user;

import app.error.ApiException;
import app.security.CurrentUser;
import app.security.TokenPrincipalResolver;
import app.user.UserDtos.AdminUserResponse;
import app.user.UserDtos.SetAdminRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
@Tag(name = "User administration", description = """
    Admin-only user management for the interim auth phase. Requires `ROLE_ADMIN`, \
    which comes from the `is_admin` flag or the `ADMIN_BOOTSTRAP_TOKEN` secret.""")
public class AdminUserController {

    private final UserRepository userRepository;
    private final UserTokenRepository tokens;
    private final TokenPrincipalResolver resolver;

    public AdminUserController(UserRepository userRepository,
                               UserTokenRepository tokens, TokenPrincipalResolver resolver) {
        this.userRepository = userRepository;
        this.tokens = tokens;
        this.resolver = resolver;
    }

    @Operation(
        summary = "List registered users",
        description = """
            Everyone who has registered. `external_id` is a generated `anon-…` handle \
            until the user links their TUM ID; unlinked accounts have nothing for a TUM \
            login to match on, so they will not survive the SAML cutover.""")
    @GetMapping("/users")
    public List<AdminUserResponse> listUsers() {
        return userRepository.findAll().stream()
            .sorted(Comparator.comparing(User::getExternalId))
            .map(this::toResponse)
            .toList();
    }

    private AdminUserResponse toResponse(User u) {
        return new AdminUserResponse(
            u.getId(), u.getExternalId(), u.getDisplayName(), u.isAdmin(),
            tokens.hasActiveToken(u.getId()));
    }

    @Operation(
        summary = "Grant or revoke admin",
        description = """
            Sets `users.is_admin`. This is the durable route to admin — the \
            `ADMIN_BOOTSTRAP_TOKEN` bootstrap credential exists only so the first \
            real admin can be promoted, and should be unset afterwards.

            You cannot change your own flag: demoting yourself could leave the \
            deployment with no administrator and no way back in without database \
            access.""")
    @ApiResponse(responseCode = "409", description = "The caller tried to change their own flag.")
    @PatchMapping("/users/{id}")
    public AdminUserResponse setAdmin(
        @PathVariable @Parameter(description = "User id to change.") UUID id,
        @RequestBody SetAdminRequest body,
        @CurrentUser String callerId
    ) {
        if (id.toString().equals(callerId)) {
            throw new ApiException(HttpStatus.CONFLICT, "You cannot change your own admin flag");
        }
        User user = userRepository.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
        user.setAdmin(body.is_admin());
        userRepository.save(user);
        // The principal cache carries the old authority for up to its TTL.
        resolver.invalidateAll();
        return toResponse(user);
    }

    @Operation(
        summary = "Revoke a user's access",
        description = """
            Revokes every session token the user holds. Their exams are untouched.""")
    @ApiResponse(responseCode = "204", description = "Revoked.")
    @DeleteMapping("/users/{id}/tokens")
    public ResponseEntity<Void> revoke(
        @PathVariable @Parameter(description = "User id to revoke.") UUID id) {
        tokens.revokeAllForUser(id);
        resolver.invalidateAll();
        return ResponseEntity.noContent().build();
    }
}
