package de.tum.cit.hestia.assessment.user;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** Response shapes for identity. */
public final class UserDtos {

    private UserDtos() {}

    @Schema(description = "The logged-in user, plus their remaining LLM quota.")
    public record MeResponse(
        UUID id,
        String display_name,
        @Schema(description = "Platform admin: may open the parse-metrics dashboard.")
        boolean is_admin,
        QuotaResponse quota
    ) {}

    @Schema(description = "Remaining LLM jobs in the current window, per kind.")
    public record QuotaResponse(int parse_remaining, int parse_limit, int solve_remaining, int solve_limit) {}
}
