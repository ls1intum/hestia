package app.solve;

import app.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SolveSectionController {

    public record SolveSectionRequest(@NotBlank String exam_id, String section_id) {}

    private final SolveSectionService service;

    public SolveSectionController(SolveSectionService service) { this.service = service; }

    /**
     * POST /api/solve-section  { "exam_id": "...", "section_id": "..." | null }
     * `section_id: null` solves the unassigned-tasks bucket.
     */
    @PostMapping("/solve-section")
    public Map<String, Object> solve(@Valid @RequestBody SolveSectionRequest req, @CurrentUser String userId) {
        SolveSectionService.Result result = service.solve(req.exam_id(), req.section_id(), userId);
        Map<String, Object> body = new HashMap<>();
        body.put("ok", true);
        body.put("status", result.status());
        body.put("requested", result.requested());
        body.put("answered", result.answered());
        return body;
    }
}
