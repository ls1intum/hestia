package app.health;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api")
@Tag(name = "Health", description = "Liveness probe.")
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
}
