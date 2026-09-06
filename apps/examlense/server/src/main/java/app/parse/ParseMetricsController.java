package app.parse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/parse-metrics — aggregate parsing metrics for the admin dashboard.
 * Requires an authenticated caller (enforced by the security filter chain).
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Parse metrics", description = "Aggregate parser-quality metrics behind the admin dashboard.")
public class ParseMetricsController {

    private final ParseMetricsService service;

    public ParseMetricsController(ParseMetricsService service) {
        this.service = service;
    }

    @Operation(
        summary = "Aggregate parse metrics",
        description = """
            Per-model parser performance across all recorded parses. Metric rows outlive \
            the exams they were measured on, so this covers deleted exams too.""")
    @GetMapping("/parse-metrics")
    public ParseMetricsService.Metrics metrics() {
        return service.aggregate();
    }
}
