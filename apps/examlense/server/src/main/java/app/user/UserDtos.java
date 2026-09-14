package app.user;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** Request/response shapes for identity, enrolment, and user administration. */
public final class UserDtos {

    private UserDtos() {}

    @Schema(description = "A new account and the token that authenticates it.")
    public record RegisterResponse(
        @Schema(description = "Session token. Store it; it is not recoverable.") String token,
        MeResponse user
    ) {}

    @Schema(description = "Attach a TUM ID to the calling account.")
    public record LinkTumIdRequest(
        @Schema(description = """
            The caller's TUM ID. Case and an @tum.de-style suffix are normalized away. \
            This is what a TUM login matches on later, so linking it is what carries \
            an account's exams across the SAML cutover.""", example = "ab12cde")
        String external_id
    ) {}

    @Schema(description = "The authenticated user, plus their remaining LLM quota.")
    public record MeResponse(
        UUID id,
        @Schema(description = "The TUM ID, or a generated `anon-…` handle if not linked yet.")
        String external_id,
        String display_name,
        @Schema(description = "The caller's effective authority, which may exceed the stored flag.")
        boolean is_admin,
        QuotaResponse quota,
        @Schema(description = "False until a TUM ID is linked; unlinked accounts do not survive the SAML cutover.")
        boolean has_tum_id
    ) {
        /** {@code isAdmin} is the caller's effective authority — see {@code MeController}. */
        public static MeResponse of(User u, boolean isAdmin, QuotaResponse quota) {
            return new MeResponse(u.getId(), u.getExternalId(), u.getDisplayName(), isAdmin, quota,
                !UserService.isAnonymous(u));
        }
    }

    @Schema(description = "Remaining LLM jobs in the current window, per kind.")
    public record QuotaResponse(int parse_remaining, int parse_limit, int solve_remaining, int solve_limit) {}

    @Schema(description = "Grant or revoke admin on a user.")
    public record SetAdminRequest(boolean is_admin) {}

    @Schema(description = "A registered user, for the admin roster.")
    public record AdminUserResponse(
        UUID id,
        String external_id,
        String display_name,
        boolean is_admin,
        boolean has_active_token
    ) {}
}
