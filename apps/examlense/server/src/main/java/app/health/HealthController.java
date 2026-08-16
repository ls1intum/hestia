package app.health;

import app.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class HealthController {

    /** Public liveness probe. No auth required. */
    @GetMapping("/healthz")
    public Map<String, Object> healthz() {
        return Map.of(
            "status", "ok",
            "time", Instant.now().toString()
        );
    }

    /** Smoke test for auth. Returns the (single-user) principal id when the token is valid. */
    @GetMapping("/me")
    public Map<String, Object> me(@CurrentUser String userId) {
        Map<String, Object> body = new HashMap<>();
        body.put("userId", userId);
        return body;
    }
}
