package app.section;
import app.shared.Patch;
import app.shared.Access;

import app.error.ApiException;
import app.security.CurrentUser;
import app.storage.SignedUrls;
import app.storage.StorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
@Tag(name = "Figures", description = """
    Images attached to a context block. The rows live here; the bytes live in storage and are \
    read through short-lived signed URLs.""")
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

    @Operation(
        summary = "List a block's figures",
        description = "Ordered by `position`. Returns metadata only — fetch a signed URL to render one.")
    @ApiResponse(responseCode = "403", description = "The block's exam belongs to another owner.")
    @ApiResponse(responseCode = "404", description = "No such block, or `blockId` is not a valid UUID.")
    @GetMapping("/blocks/{blockId}/figures")
    public List<SectionDtos.FigureDto> list(@PathVariable String blockId, @CurrentUser String userId) {
        requireBlock(Access.id(blockId), userId);
        return figureRepository.findByBlockIdOrderByPositionAsc(Access.id(blockId))
            .stream().map(SectionDtos.FigureDto::from).toList();
    }

    @Operation(
        summary = "Upload a figure to a block",
        description = """
            `multipart/form-data` with a `file` part and an optional `position` (defaults to the \
            end of the block). The image type is taken from the filename extension, falling back \
            to the content type; only `png`, `jpg`, `jpeg`, `webp`, and `gif` are accepted.""")
    @ApiResponse(responseCode = "400", description = "Unsupported image type.")
    @ApiResponse(responseCode = "403", description = "The block's exam belongs to another owner.")
    @ApiResponse(responseCode = "404", description = "No such block, or `blockId` is not a valid UUID.")
    // `consumes` is what makes the spec advertise multipart/form-data; without it
    // springdoc documents the (correct) file schema under application/json.
    @PostMapping(value = "/blocks/{blockId}/figures", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SectionDtos.FigureDto upload(@PathVariable String blockId,
                                 @RequestParam("file")
                                 @Parameter(description = "PNG, JPEG, WebP, or GIF image.")
                                 MultipartFile file,
                                 @RequestParam(value = "position", required = false)
                                 @Parameter(description = "Insertion index; appended when omitted.")
                                 Integer position,
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

    @Operation(
        summary = "Update a figure",
        description = "Sparse update accepting `caption` and `position`. The image bytes are immutable — re-upload to replace one.")
    @ApiResponse(responseCode = "403", description = "The figure's exam belongs to another owner.")
    @ApiResponse(responseCode = "404", description = "No such figure, or `id` is not a valid UUID.")
    @PatchMapping("/figures/{id}")
    public SectionDtos.FigureDto patch(@PathVariable String id, @RequestBody Map<String, Object> body,
                                @CurrentUser String userId) {
        SectionFigure fig = loadFigure(id, userId);
        if (Patch.has(body, "caption")) fig.setCaption(Patch.str(body.get("caption")));
        if (Patch.has(body, "position")) fig.setPosition(Patch.intVal(body.get("position")));
        return SectionDtos.FigureDto.from(figureRepository.save(fig));
    }

    @Operation(
        summary = "Delete a figure",
        description = "Removes the row; the stored object is deleted on a best-effort basis and a storage failure does not block it.")
    @ApiResponse(responseCode = "204", description = "Deleted.")
    @ApiResponse(responseCode = "403", description = "The figure's exam belongs to another owner.")
    @ApiResponse(responseCode = "404", description = "No such figure, or `id` is not a valid UUID.")
    @DeleteMapping("/figures/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, @CurrentUser String userId) {
        SectionFigure fig = loadFigure(id, userId);
        try { storage.delete(FIGURE_BUCKET, fig.getStoragePath()); } catch (RuntimeException ignored) {}
        figureRepository.delete(fig);
        return ResponseEntity.noContent().build();
    }

    /** Short-lived signed URL for rendering the figure via <img src>. */
    @Operation(
        summary = "Get a signed URL for a figure",
        description = """
            Returns `{ signed_url }` — an absolute, HMAC-signed `/api/files/**` URL valid for \
            one hour. It needs no bearer token, so it can go straight into an `<img src>`.""")
    @ApiResponse(responseCode = "403", description = "The figure's exam belongs to another owner.")
    @ApiResponse(responseCode = "404", description = "No such figure, or `id` is not a valid UUID.")
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
