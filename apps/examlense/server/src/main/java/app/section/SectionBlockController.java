package app.section;
import app.shared.Patch;
import app.shared.Access;

import app.error.ApiException;
import app.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Blocks", description = """
    Context blocks — the non-question prose inside a section. They share one `position` \
    sequence with the section's tasks, which is how the editor interleaves them.""")
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

    @Operation(
        summary = "List an exam's context blocks",
        description = "Every block across all of the exam's sections, ordered by `position`.")
    @ApiResponse(responseCode = "403", description = "The exam belongs to another owner.")
    @ApiResponse(responseCode = "404", description = "No such exam, or `examId` is not a valid UUID.")
    @GetMapping("/exams/{examId}/blocks")
    public List<SectionDtos.BlockDto> list(@PathVariable String examId, @CurrentUser String userId) {
        access.requireExam(Access.id(examId), userId);
        return blockRepository.findByExamIdOrderByPositionAsc(Access.id(examId))
            .stream().map(SectionDtos.BlockDto::from).toList();
    }

    @Operation(
        summary = "Create a context block",
        description = "Inserts at `position` (default 0) within the section, shifting later items down.")
    @ApiResponse(responseCode = "403", description = "The exam belongs to another owner.")
    @ApiResponse(responseCode = "404", description = "No such exam, or an id is not a valid UUID.")
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

    @Operation(
        summary = "Update a context block",
        description = """
            Sparse update accepting `content`, `kind`, `position`, and `section_id`.

            Moving a block via `section_id` is checked: the target section must belong to the \
            same exam, so a block cannot be reassigned across exams.""")
    @ApiResponse(responseCode = "400", description = "The target `section_id` is unknown or belongs to a different exam.")
    @ApiResponse(responseCode = "403", description = "The block's exam belongs to another owner.")
    @ApiResponse(responseCode = "404", description = "No such block, or `id` is not a valid UUID.")
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

    @Operation(summary = "Delete a context block", description = "Its figures cascade with it.")
    @ApiResponse(responseCode = "204", description = "Deleted.")
    @ApiResponse(responseCode = "403", description = "The block's exam belongs to another owner.")
    @ApiResponse(responseCode = "404", description = "No such block, or `id` is not a valid UUID.")
    @DeleteMapping("/blocks/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, @CurrentUser String userId) {
        SectionBlock b = load(id, userId);
        blockRepository.delete(b);
        return ResponseEntity.noContent().build();
    }

    @Operation(
        summary = "Delete every block in a section",
        description = "Bulk delete used when the editor clears a section. Deleting the section itself does this too.")
    @ApiResponse(responseCode = "204", description = "Deleted.")
    @ApiResponse(responseCode = "403", description = "The exam belongs to another owner.")
    @ApiResponse(responseCode = "404", description = "No such exam, or an id is not a valid UUID.")
    @DeleteMapping("/exams/{examId}/blocks")
    public ResponseEntity<Void> deleteBySection(@PathVariable String examId,
                                                 @RequestParam("section_id")
                                                 @Parameter(description = "Section whose blocks are removed.")
                                                 String sectionId,
                                                 @CurrentUser String userId) {
        access.requireExam(Access.id(examId), userId);
        blockRepository.deleteByExamIdAndSectionId(Access.id(examId), Access.id(sectionId));
        return ResponseEntity.noContent().build();
    }

    private SectionBlock load(String id, String userId) {
        return access.requireOwnedChild(blockRepository, id, userId, SectionBlock::getExamId, "Block");
    }
}
