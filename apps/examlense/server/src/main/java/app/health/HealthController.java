package app.health;

import app.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
@Tag(name = "Health", description = "Liveness probe and auth smoke test.")
public class HealthController {

    /** Public liveness probe. No auth required. */
    @Operation(
        summary = "Liveness probe",
        description = "Returns `{ status: \"ok\", time: <ISO-8601> }`. The only endpoint that needs no token.")
    @SecurityRequirements
    @GetMapping("/healthz")
    public Map<String, Object> healthz() {
        return Map.of(
            "status", "ok",
            "time", Instant.now().toString()
        );
    }

    /** Smoke test for auth. Returns the (single-user) principal id when the token is valid. */
    @Operation(
        summary = "Current principal",
        description = "Returns `{ userId }` for the single seeded user. Use it to check that a token is accepted.")
    @GetMapping("/me")
    public Map<String, Object> me(@CurrentUser String userId) {
        Map<String, Object> body = new HashMap<>();
        body.put("userId", userId);
        return body;
    }
}
