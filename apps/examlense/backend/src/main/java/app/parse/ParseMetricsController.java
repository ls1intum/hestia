package app.parse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/parse-metrics — aggregate parsing metrics for the admin dashboard.
 * Requires an authenticated caller (enforced by the security filter chain).
 */
@RestController
@RequestMapping("/api")
public class ParseMetricsController {

    private final ParseMetricsService service;

    public ParseMetricsController(ParseMetricsService service) {
        this.service = service;
    }

    @GetMapping("/parse-metrics")
    public ParseMetricsService.Metrics metrics() {
        return service.aggregate();
    }
}
