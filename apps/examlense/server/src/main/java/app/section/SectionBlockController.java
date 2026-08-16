package app.section;
import app.shared.Patch;
import app.shared.Access;

import app.error.ApiException;
import app.security.CurrentUser;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class SectionBlockController {

    public record CreateBlockRequest(String exam_id, String section_id, Integer position,
                                      String content, String kind) {}

    private final SectionBlockRepository blockRepository;
    private final Access access;
    private final SectionService sectionService;

    public SectionBlockController(SectionBlockRepository blockRepository, Access access, SectionService sectionService) {
        this.blockRepository = blockRepository;
        this.access = access;
        this.sectionService = sectionService;
    }

    @GetMapping("/exams/{examId}/blocks")
    public List<SectionDtos.BlockDto> list(@PathVariable String examId, @CurrentUser String userId) {
        access.requireExam(Access.id(examId), userId);
        return blockRepository.findByExamIdOrderByPositionAsc(Access.id(examId))
            .stream().map(SectionDtos.BlockDto::from).toList();
    }

    @PostMapping("/blocks")
    public SectionDtos.BlockDto create(@RequestBody CreateBlockRequest req, @CurrentUser String userId) {
        UUID examId = Access.id(req.exam_id());
        access.requireExam(examId, userId);
        SectionBlock b = new SectionBlock();
        b.setExamId(examId);
        b.setSectionId(Access.id(req.section_id()));
        b.setPosition(req.position() == null ? 0 : req.position());
        if (req.content() != null) b.setContent(req.content());
        if (req.kind() != null) b.setKind(req.kind());
        return SectionDtos.BlockDto.from(sectionService.addBlock(b));
    }

    @PatchMapping("/blocks/{id}")
    public SectionDtos.BlockDto patch(@PathVariable String id, @RequestBody Map<String, Object> body,
                               @CurrentUser String userId) {
        SectionBlock b = load(id, userId);
        if (Patch.has(body, "content")) b.setContent(Patch.str(body.get("content")));
        if (Patch.has(body, "kind")) b.setKind(Patch.str(body.get("kind")));
        if (Patch.has(body, "position")) b.setPosition(Patch.intVal(body.get("position")));
        if (Patch.has(body, "section_id")) {
            UUID sectionId = Patch.uuid(body.get("section_id"));
            // Guard cross-exam reassignment: the target section must belong to this block's exam.
            if (sectionId != null) access.requireSectionInExam(sectionId, b.getExamId());
            b.setSectionId(sectionId);
        }
        return SectionDtos.BlockDto.from(blockRepository.save(b));
    }

    @DeleteMapping("/blocks/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, @CurrentUser String userId) {
        SectionBlock b = load(id, userId);
        blockRepository.delete(b);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/exams/{examId}/blocks")
    public ResponseEntity<Void> deleteBySection(@PathVariable String examId,
                                                 @RequestParam("section_id") String sectionId,
                                                 @CurrentUser String userId) {
        access.requireExam(Access.id(examId), userId);
        blockRepository.deleteByExamIdAndSectionId(Access.id(examId), Access.id(sectionId));
        return ResponseEntity.noContent().build();
    }

    private SectionBlock load(String id, String userId) {
        return access.requireOwnedChild(blockRepository, id, userId, SectionBlock::getExamId, "Block");
    }
}
