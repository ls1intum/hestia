package app.section;
import app.shared.Patch;
import app.shared.Access;

import app.error.ApiException;
import app.security.CurrentUser;
import app.storage.SignedUrls;
import app.storage.StorageService;
import java.io.IOException;
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
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class FigureController {

    private static final String FIGURE_BUCKET = "exam-figures";
    private static final long SIGNED_URL_TTL_SECONDS = 3600;

    private final SectionFigureRepository figureRepository;
    private final SectionBlockRepository blockRepository;
    private final Access access;
    private final StorageService storage;
    private final SignedUrls signedUrls;

    public FigureController(SectionFigureRepository figureRepository, SectionBlockRepository blockRepository,
                            Access access, StorageService storage, SignedUrls signedUrls) {
        this.figureRepository = figureRepository;
        this.blockRepository = blockRepository;
        this.access = access;
        this.storage = storage;
        this.signedUrls = signedUrls;
    }

    @GetMapping("/blocks/{blockId}/figures")
    public List<SectionDtos.FigureDto> list(@PathVariable String blockId, @CurrentUser String userId) {
        requireBlock(Access.id(blockId), userId);
        return figureRepository.findByBlockIdOrderByPositionAsc(Access.id(blockId))
            .stream().map(SectionDtos.FigureDto::from).toList();
    }

    @PostMapping("/blocks/{blockId}/figures")
    public SectionDtos.FigureDto upload(@PathVariable String blockId,
                                 @RequestParam("file") MultipartFile file,
                                 @RequestParam(value = "position", required = false) Integer position,
                                 @CurrentUser String userId) throws IOException {
        UUID bid = Access.id(blockId);
        SectionBlock block = requireBlock(bid, userId);

        String ext = extensionFor(file);
        if (ext.isEmpty() || !ext.matches("png|jpg|jpeg|webp|gif")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported image type");
        }

        SectionFigure fig = new SectionFigure();
        fig.setBlockId(bid);
        fig.setSource("upload");
        fig.setPosition(position != null ? position : figureRepository.findByBlockIdOrderByPositionAsc(bid).size());

        String path = userId + "/" + block.getExamId() + "/" + fig.getId() + "." + ext;
        fig.setStoragePath(path);
        // Save the row first, then store the object — a failed DB write can't
        // leave an orphaned file; a failed store leaves a row the delete
        // endpoint can still clean up.
        SectionDtos.FigureDto dto = SectionDtos.FigureDto.from(figureRepository.save(fig));
        try {
            storage.store(FIGURE_BUCKET, path, file.getBytes());
        } catch (RuntimeException e) {
            figureRepository.delete(fig);
            throw e;
        }
        return dto;
    }

    @PatchMapping("/figures/{id}")
    public SectionDtos.FigureDto patch(@PathVariable String id, @RequestBody Map<String, Object> body,
                                @CurrentUser String userId) {
        SectionFigure fig = loadFigure(id, userId);
        if (Patch.has(body, "caption")) fig.setCaption(Patch.str(body.get("caption")));
        if (Patch.has(body, "position")) fig.setPosition(Patch.intVal(body.get("position")));
        return SectionDtos.FigureDto.from(figureRepository.save(fig));
    }

    @DeleteMapping("/figures/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, @CurrentUser String userId) {
        SectionFigure fig = loadFigure(id, userId);
        try { storage.delete(FIGURE_BUCKET, fig.getStoragePath()); } catch (RuntimeException ignored) {}
        figureRepository.delete(fig);
        return ResponseEntity.noContent().build();
    }

    /** Short-lived signed URL for rendering the figure via <img src>. */
    @GetMapping("/figures/{id}/signed-url")
    public Map<String, Object> signedUrl(@PathVariable String id, @CurrentUser String userId) {
        SectionFigure fig = loadFigure(id, userId);
        return Map.of("signed_url", signedUrls.buildUrl(FIGURE_BUCKET, fig.getStoragePath(), SIGNED_URL_TTL_SECONDS));
    }

    private SectionBlock requireBlock(UUID blockId, String userId) {
        SectionBlock block = blockRepository.findById(blockId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Block not found"));
        access.requireExam(block.getExamId(), userId);
        return block;
    }

    private SectionFigure loadFigure(String id, String userId) {
        SectionFigure fig = figureRepository.findById(Access.id(id))
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Figure not found"));
        requireBlock(fig.getBlockId(), userId);
        return fig;
    }

    private static String extensionFor(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name != null && name.contains(".")) {
            String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase();
            if (ext.matches("[a-z0-9]{1,5}")) return ext;
        }
        String ct = file.getContentType();
        if (ct == null) return "";
        return switch (ct) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            default -> "";
        };
    }
}
