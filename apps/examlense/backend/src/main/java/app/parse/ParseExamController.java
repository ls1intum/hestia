package app.parse;

import app.ai.ParserStrategy;
import app.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class ParseExamController {

    public record ParseExamRequest(
        @NotBlank String exam_id,
        @NotBlank String storage_path,
        String language_hint,
        String parser_model,
        Boolean fast_mode
    ) {}

    private final ParseExamService service;

    public ParseExamController(ParseExamService service) {
        this.service = service;
    }

    /**
     * POST /api/parse-exam-pdf  { "exam_id": "...", "storage_path": "...", ... }
     *
     * Verifies ownership synchronously, dispatches the extraction onto the
     * background pool, and returns 202 immediately. The frontend observes
     * `exams.parse_phase` for progress (via SSE once Phase 2c lands).
     */
    @PostMapping("/parse-exam-pdf")
    public ResponseEntity<Map<String, Object>> parse(
        @Valid @RequestBody ParseExamRequest req,
        @CurrentUser String userId
    ) {
        // Start the clock here (not inside run()) so the recorded duration tracks
        // the closest-to-e2e server-side window: preflight + queue wait + pipeline.
        long requestNanos = System.nanoTime();
        ParserStrategy strategy = service.preflight(req.exam_id(), userId, req.parser_model());
        service.runAsync(
            req.exam_id(),
            userId,
            req.storage_path(),
            req.language_hint(),
            strategy,
            Boolean.TRUE.equals(req.fast_mode()),
            requestNanos
        );
        return ResponseEntity.accepted().body(Map.of(
            "ok", true,
            "exam_id", req.exam_id(),
            "status", "parsing"
        ));
    }
}
