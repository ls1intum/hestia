package app.section;
import app.shared.Patch;
import app.shared.Access;

import app.error.ApiException;
import app.lgh.TaskGoalGenerationService;
import app.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@Tag(name = "Sections", description = """
    Sections group an exam's tasks and context blocks by `position`. Confirming a section is \
    what gates solving and triggers learning-goal generation.""")
public class SectionController {

    public record CreateSectionRequest(String exam_id, Integer position, String name) {}

    private final SectionRepository sectionRepository;
    private final Access access;
    private final SectionService sectionService;
    private final TaskGoalGenerationService goalGeneration;

    public SectionController(SectionRepository sectionRepository, Access access, SectionService sectionService,
                             TaskGoalGenerationService goalGeneration) {
        this.sectionRepository = sectionRepository;
        this.access = access;
        this.sectionService = sectionService;
        this.goalGeneration = goalGeneration;
    }

    @Operation(summary = "List an exam's sections", description = "Ordered by `position`.")
    @ApiResponse(responseCode = "403", description = "The exam belongs to another owner.")
    @ApiResponse(responseCode = "404", description = "No such exam, or `examId` is not a valid UUID.")
    @GetMapping("/exams/{examId}/sections")
    public List<SectionDtos.SectionDto> list(@PathVariable String examId, @CurrentUser String userId) {
        access.requireExam(Access.id(examId), userId);
        return sectionRepository.findByExamIdOrderByPositionAsc(Access.id(examId))
            .stream().map(SectionDtos.SectionDto::from).toList();
    }

    @Operation(
        summary = "Create a section",
        description = "Inserts at `position` (default 0), shifting later sections down.")
    @ApiResponse(responseCode = "403", description = "The exam belongs to another owner.")
    @ApiResponse(responseCode = "404", description = "No such exam, or `exam_id` is not a valid UUID.")
    @PostMapping("/sections")
    public SectionDtos.SectionDto create(@RequestBody CreateSectionRequest req, @CurrentUser String userId) {
        UUID examId = Access.id(req.exam_id());
        access.requireExam(examId, userId);
        Section s = new Section();
        s.setExamId(examId);
        s.setPosition(req.position() == null ? 0 : req.position());
        if (req.name() != null) s.setName(req.name());
        return SectionDtos.SectionDto.from(sectionService.addSection(s));
    }

    @Operation(
        summary = "Update a section",
        description = """
            Sparse update accepting `name` and `position` only.

            `confirmed_at` is not patchable — use the confirm/unconfirm endpoints, which also \
            do the goal and answer cleanup a raw patch would skip. `solve_started_at` and \
            `goals_started_at` are internal locks and are never writable.""")
    @ApiResponse(responseCode = "403", description = "The section's exam belongs to another owner.")
    @ApiResponse(responseCode = "404", description = "No such section, or `id` is not a valid UUID.")
    @PatchMapping("/sections/{id}")
    public SectionDtos.SectionDto patch(@PathVariable String id, @RequestBody java.util.Map<String, Object> body,
                                 @CurrentUser String userId) {
        Section s = load(id, userId);
        // Only user-editable fields. `confirmed_at` has dedicated endpoints
        // (confirm/unconfirm do goal + answer cleanup a raw patch would skip)
        // and `solve_started_at` / `goals_started_at` are internal CAS locks.
        if (Patch.has(body, "name")) s.setName(Patch.str(body.get("name")));
        if (Patch.has(body, "position")) s.setPosition(Patch.intVal(body.get("position")));
        return SectionDtos.SectionDto.from(sectionRepository.save(s));
    }

    /**
     * Full server-side cleanup: unconfirm (drops AI answers + detaches learning
     * goals) if needed, then delete the section's tasks and blocks with it —
     * not trusting the frontend to call the delete-by-section endpoints first.
     */
    @Operation(
        summary = "Delete a section",
        description = """
            Unconfirms first if needed — dropping AI answers and detaching learning goals — \
            then deletes the section together with its tasks and blocks. The task cleanup is \
            done here rather than by the database: `tasks.section_id` is `ON DELETE SET NULL`, \
            so relying on the FK would orphan the tasks instead of removing them.""")
    @ApiResponse(responseCode = "204", description = "Deleted.")
    @ApiResponse(responseCode = "403", description = "The section's exam belongs to another owner.")
    @ApiResponse(responseCode = "404", description = "No such section, or `id` is not a valid UUID.")
    @DeleteMapping("/sections/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, @CurrentUser String userId) {
        Section s = load(id, userId);
        sectionService.deleteSection(s);
        return ResponseEntity.noContent().build();
    }

    @Operation(
        summary = "Confirm a section",
        description = """
            Marks the section ready to solve and kicks off learning-goal generation in the \
            background: the section's context blocks and tasks are posted to LearningGoalHub \
            and the returned goal ids are stored on the tasks.

            Goal generation is fire-and-forget and never fails the confirm — if LGH is down the \
            section still confirms, just without goals. Re-confirming deletes the previously \
            generated goals first, since LGH does not deduplicate. Watch the `tasks` SSE event \
            to know when generation finished.""")
    @ApiResponse(responseCode = "403", description = "The section's exam belongs to another owner.")
    @ApiResponse(responseCode = "404", description = "No such section, or `id` is not a valid UUID.")
    @PostMapping("/sections/{id}/confirm")
    public SectionDtos.SectionDto confirm(@PathVariable String id, @CurrentUser String userId) {
        Section s = load(id, userId);
        s.setConfirmedAt(OffsetDateTime.now());
        SectionDtos.SectionDto dto = SectionDtos.SectionDto.from(sectionRepository.save(s));
        // Fire-and-forget LGH goal generation; must never fail the confirm.
        try {
            goalGeneration.dispatchGenerate(s.getExamId(), s.getId());
        } catch (RuntimeException ignored) {}
        return dto;
    }

    @Operation(
        summary = "Unconfirm a section",
        description = """
            Reopens the section for editing: clears `confirmed_at`, drops the AI answers for its \
            tasks, and clears their learning-goal ids, deleting the goals from LGH on a \
            best-effort basis.""")
    @ApiResponse(responseCode = "403", description = "The section's exam belongs to another owner.")
    @ApiResponse(responseCode = "404", description = "No such section, or `id` is not a valid UUID.")
    @PostMapping("/sections/{id}/unconfirm")
    public SectionDtos.SectionDto unconfirm(@PathVariable String id, @CurrentUser String userId) {
        Section s = load(id, userId);
        sectionService.unconfirmSection(s);
        return SectionDtos.SectionDto.from(sectionRepository.findById(s.getId()).orElse(s));
    }

    /** Load a section and verify the caller owns its exam. */
    private Section load(String id, String userId) {
        return access.requireOwnedChild(sectionRepository, id, userId, Section::getExamId, "Section");
    }
}
