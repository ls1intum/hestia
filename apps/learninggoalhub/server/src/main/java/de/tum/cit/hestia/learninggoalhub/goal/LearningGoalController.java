package de.tum.cit.hestia.learninggoalhub.goal;

import de.tum.cit.hestia.learninggoalhub.taxonomy.BloomLevel;
import de.tum.cit.hestia.learninggoalhub.taxonomy.SoloLevel;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The learning goals of a course. Reads go to {@link LearningGoalQueryService}, changes to
 * {@link LearningGoalEditService}, which documents what each change does.
 */
@RestController
@RequestMapping("/api/courses/{courseId}/learning-goals")
public class LearningGoalController {

    private final LearningGoalQueryService queries;
    private final LearningGoalEditService edits;

    public LearningGoalController(LearningGoalQueryService queries, LearningGoalEditService edits) {
        this.queries = queries;
        this.edits = edits;
    }

    @GetMapping
    public PagedModel<LearningGoalResponse> list(@PathVariable Long courseId,
                                                 @RequestParam(required = false) GoalStatus status,
                                                 @Parameter(description = "Optional extraction-tier filter: SKILL or KNOWLEDGE. Omit for all goals.")
                                                 @RequestParam(required = false) GoalRole role,
                                                 @ParameterObject @PageableDefault(size = 50, sort = "id", direction = Sort.Direction.ASC)
                                                 Pageable pageable) {
        // PagedModel serializes a stable {content, page} JSON shape, unlike PageImpl whose format
        // is an implementation detail that has changed across Spring Data versions.
        return new PagedModel<>(queries.page(courseId, status, role, pageable));
    }

    /**
     * Goals grouped by the hierarchy node (module/session/exercise) they belong to, for API
     * consumers that need per-session granularity. Groups follow node creation order — the module
     * root first, then sessions/exercises in document order; goals without a hierarchy node come
     * last in a group whose {@code nodeId}, {@code level} and {@code label} are {@code null}.
     * Nodes without any (matching) goals are omitted.
     *
     * <p>An optional {@code nodeId} narrows the result to the goals of that single hierarchy node
     * (so consumers can fetch one session without loading the whole course); the node-less bucket is
     * excluded, and an unknown {@code nodeId} simply yields an empty list.
     */
    @GetMapping("/by-session")
    public List<SessionGoalsResponse> listBySession(@PathVariable Long courseId,
                                                    @Parameter(description = "Optional review-status filter: PENDING or APPROVED. Omit for all goals.")
                                                    @RequestParam(required = false) GoalStatus status,
                                                    @Parameter(description = "Optional extraction-tier filter: SKILL or KNOWLEDGE. Omit for all goals.")
                                                    @RequestParam(required = false) GoalRole role,
                                                    @Parameter(description = "Optional id of a single hierarchy node (module/session/exercise) to return only that group. "
                                                            + "Read the id from the 'nodeId' field of this endpoint's unfiltered response. An unknown id yields an empty list.")
                                                    @RequestParam(required = false) Long nodeId) {
        return queries.bySession(courseId, status, role, nodeId);
    }

    @PostMapping("/terminal")
    @ResponseStatus(HttpStatus.CREATED)
    public LearningGoalResponse createTerminalSkill(@PathVariable Long courseId,
                                                    @RequestBody CreateTerminalSkillRequest request) {
        return edits.createTerminalSkill(courseId, request.text());
    }

    /** Returns transient AI suggestions grounded in the already extracted course goals. */
    @PostMapping("/skill-suggestions")
    public List<SkillSuggestionResponse> suggestTerminalSkills(@PathVariable Long courseId) {
        return edits.suggestTerminalSkills(courseId).stream()
                .map(suggestion -> new SkillSuggestionResponse(suggestion.text(), suggestion.shortLabel()))
                .toList();
    }

    @PostMapping("/terminal/generated")
    @ResponseStatus(HttpStatus.CREATED)
    public LearningGoalResponse createGeneratedTerminalSkill(@PathVariable Long courseId,
                                                             @RequestBody CreateGeneratedTerminalSkillRequest request) {
        return edits.createGeneratedTerminalSkill(courseId, request.text(), request.shortLabel());
    }

    @PostMapping("/{goalId}/subtree")
    public LearningGoalResponse generateSubtree(@PathVariable Long courseId,
                                                @PathVariable Long goalId) {
        return edits.generateSubtree(courseId, goalId);
    }

    @PostMapping("/{goalId}/children")
    @ResponseStatus(HttpStatus.CREATED)
    public LearningGoalResponse addChild(@PathVariable Long courseId,
                                         @PathVariable Long goalId,
                                         @RequestBody AddChildRequest request) {
        return edits.addChild(courseId, goalId, request.text(), request.shortLabel(), request.role());
    }

    @PatchMapping("/{goalId}")
    public LearningGoalResponse update(@PathVariable Long courseId,
                                       @PathVariable Long goalId,
                                       @RequestBody UpdateLearningGoalRequest request) {
        return edits.update(courseId, goalId, request.text(), request.status(), request.bloomLevel(),
                request.soloLevel());
    }

    @DeleteMapping("/{goalId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long courseId, @PathVariable Long goalId) {
        edits.delete(courseId, goalId);
    }

    @GetMapping(value = "/export.csv", produces = "text/csv")
    public void exportCsv(@PathVariable Long courseId, HttpServletResponse response) throws IOException {
        queries.exportCsv(courseId, () -> {
            response.setContentType(MediaType.parseMediaType("text/csv").toString());
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setHeader("Content-Disposition",
                    "attachment; filename=\"course-" + courseId + "-learning-goals.csv\"");
            return response.getWriter();
        });
    }

    /** Body of the "add a terminal skill" review action: just the instructor-typed skill text. */
    public record CreateTerminalSkillRequest(String text) {
    }

    public record SkillSuggestionResponse(String text, String shortLabel) {
    }

    public record CreateGeneratedTerminalSkillRequest(String text, String shortLabel) {
    }

    public record AddChildRequest(String text, String shortLabel, GoalRole role) {
    }

    /** Partial update: only non-null fields are applied (levels can be set, not cleared). */
    public record UpdateLearningGoalRequest(String text,
                                            GoalStatus status,
                                            BloomLevel bloomLevel,
                                            SoloLevel soloLevel) {
    }
}
