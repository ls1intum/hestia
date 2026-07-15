package app.section;
import app.shared.Patch;
import app.shared.Access;

import app.error.ApiException;
import app.lgh.TaskGoalGenerationService;
import app.security.CurrentUser;
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

    @GetMapping("/exams/{examId}/sections")
    public List<SectionDtos.SectionDto> list(@PathVariable String examId, @CurrentUser String userId) {
        access.requireExam(Access.id(examId), userId);
        return sectionRepository.findByExamIdOrderByPositionAsc(Access.id(examId))
            .stream().map(SectionDtos.SectionDto::from).toList();
    }

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
    @DeleteMapping("/sections/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, @CurrentUser String userId) {
        Section s = load(id, userId);
        sectionService.deleteSection(s);
        return ResponseEntity.noContent().build();
    }

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
