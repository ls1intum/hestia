package app.parse.figures;

import app.exam.Exam;
import app.exam.ExamRepository;
import app.section.SectionFigure;
import app.section.SectionFigureRepository;
import app.sse.SseHub;
import app.storage.StorageService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Fills parsed figure blocks with images cut from the source PDF.
 *
 * <p>Runs after the exam has already reached {@code draft}, not inside the parse.
 * Rendering is the heaviest and most decoder-dependent step in the pipeline, and
 * an exam whose tasks parsed fine must not fail because a figure could not be
 * cropped. Everything here is best-effort: any failure leaves the block empty,
 * which is precisely the "upload a screenshot" state users have today, so the
 * worst case of this feature is the product as it already ships.
 */
@Service
public class FigureExtractionService {

    private static final Logger log = LoggerFactory.getLogger(FigureExtractionService.class);
    private static final String PDF_BUCKET = "exam-pdfs";
    private static final String FIGURE_BUCKET = "exam-figures";

    /** Bounds the work one pathological document can create. */
    private static final int MAX_FIGURES = 60;

    private final ExamRepository examRepository;
    private final SectionFigureRepository figureRepository;
    private final StorageService storage;
    private final FigureRegionDetector detector;
    private final FigureMatcher matcher;
    private final PdfFigureCropper cropper;
    private final SseHub sse;
    private final boolean enabled;

    public FigureExtractionService(
        ExamRepository examRepository,
        SectionFigureRepository figureRepository,
        StorageService storage,
        FigureRegionDetector detector,
        FigureMatcher matcher,
        PdfFigureCropper cropper,
        SseHub sse,
        @Value("${app.parse.figure-extraction.enabled:true}") boolean enabled
    ) {
        this.examRepository = examRepository;
        this.figureRepository = figureRepository;
        this.storage = storage;
        this.detector = detector;
        this.matcher = matcher;
        this.cropper = cropper;
        this.sse = sse;
        this.enabled = enabled;
    }

    /**
     * Kicks off extraction on its own pool. Takes the storage path rather than the
     * PDF bytes: queuing a 10 MB array would pin it for the whole wait, whereas
     * re-reading from local storage costs milliseconds.
     */
    @Async("figureExecutor")
    public void extractAsync(UUID examId, String userId, String storagePath,
                             List<FigurePlacement> placements) {
        try {
            extract(examId, userId, storagePath, placements);
        } catch (Exception e) {
            log.warn("figure-extract[{}] aborted: {}", examId, e.toString());
        }
    }

    void extract(UUID examId, String userId, String storagePath, List<FigurePlacement> placements) {
        if (!enabled || placements == null || placements.isEmpty()) return;

        List<FigurePlacement> withPage = placements.stream()
            .filter(p -> p.pageNumber() != null)
            .limit(MAX_FIGURES)
            .toList();
        if (withPage.isEmpty()) {
            log.info("figure-extract[{}] no figure carried a page number — nothing to do", examId);
            return;
        }

        OffsetDateTime parsedAt = examRepository.findById(examId).map(Exam::getParsedAt).orElse(null);

        byte[] pdf = storage.download(PDF_BUCKET, storagePath);
        if (pdf == null) {
            log.warn("figure-extract[{}] source PDF missing at {}", examId, storagePath);
            return;
        }

        Map<UUID, String> captions = new LinkedHashMap<>();
        for (FigurePlacement p : withPage) {
            if (p.caption() != null && !p.caption().isBlank()) captions.put(p.blockId(), p.caption());
        }

        long start = System.nanoTime();
        int stored = 0;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            Map<Integer, FigureMatcher.PageContent> pages = scanPages(examId, doc, withPage);
            if (pages.isEmpty()) return;

            List<FigureMatcher.Match> matches = matcher.match(withPage, pages);
            stored = writeCrops(examId, userId, doc, matches, captions, parsedAt);
        } catch (Exception e) {
            log.warn("figure-extract[{}] failed: {}", examId, e.toString());
        }

        log.info("figure-extract[{}] stored={} of {} figure blocks in {}ms",
            examId, stored, withPage.size(), (System.nanoTime() - start) / 1_000_000L);
        if (stored > 0) sse.examUpdated(examId);
    }

    /** Runs the content stream of every page that carries a figure. No rendering yet. */
    private Map<Integer, FigureMatcher.PageContent> scanPages(
        UUID examId, PDDocument doc, List<FigurePlacement> placements) {

        Map<Integer, List<Rectangle2D>> regionsByPage = new LinkedHashMap<>();
        Map<Integer, List<FigureRegion>> glyphsByPage = new LinkedHashMap<>();

        for (int pageNumber : new TreeSet<>(placements.stream().map(FigurePlacement::pageNumber).toList())) {
            int index = pageNumber - 1;
            if (index < 0 || index >= doc.getNumberOfPages()) continue;
            try {
                PDPage page = doc.getPage(index);
                List<FigureRegion> regions = PdfFigureRegionEngine.scan(page);
                regionsByPage.put(pageNumber, new ArrayList<>(detector.detect(page, regions)));
                glyphsByPage.put(pageNumber, regions.stream()
                    .filter(r -> r.kind() == FigureRegion.Kind.GLYPH).toList());
            } catch (Exception e) {
                log.warn("figure-extract[{}] page {} scan failed: {}", examId, pageNumber, e.toString());
            }
        }

        FigureRegionDetector.demoteRepeated(regionsByPage);

        Map<Integer, FigureMatcher.PageContent> out = new LinkedHashMap<>();
        regionsByPage.forEach((page, regions) -> {
            if (!regions.isEmpty()) {
                out.put(page, new FigureMatcher.PageContent(
                    regions, glyphsByPage.getOrDefault(page, List.of())));
            }
        });
        return out;
    }

    /** Renders each matched page once, crops every figure on it, then moves on. */
    private int writeCrops(UUID examId, String userId, PDDocument doc,
                           List<FigureMatcher.Match> matches, Map<UUID, String> captions,
                           OffsetDateTime parsedAt) {
        Map<Integer, List<FigureMatcher.Match>> byPage = new LinkedHashMap<>();
        for (FigureMatcher.Match m : matches) {
            byPage.computeIfAbsent(m.pageNumber(), k -> new ArrayList<>()).add(m);
        }

        PDFRenderer renderer = new PDFRenderer(doc);
        int stored = 0;
        for (Map.Entry<Integer, List<FigureMatcher.Match>> e : byPage.entrySet()) {
            BufferedImage rendered;
            try {
                rendered = renderer.renderImageWithDPI(
                    e.getKey() - 1, PdfFigureCropper.DPI, ImageType.RGB);
            } catch (Exception ex) {
                log.warn("figure-extract[{}] page {} render failed: {}", examId, e.getKey(), ex.toString());
                continue;
            }
            PDPage page = doc.getPage(e.getKey() - 1);
            for (FigureMatcher.Match m : e.getValue()) {
                if (isStale(examId, parsedAt)) {
                    log.info("figure-extract[{}] exam re-parsed mid-run — stopping", examId);
                    return stored;
                }
                if (writeOne(examId, userId, m, captions.get(m.blockId()), page, rendered)) stored++;
            }
        }
        return stored;
    }

    private boolean writeOne(UUID examId, String userId, FigureMatcher.Match match,
                             String caption, PDPage page, BufferedImage rendered) {
        Optional<PdfFigureCropper.Crop> crop;
        try {
            crop = cropper.crop(rendered, page, match.region());
        } catch (Exception e) {
            log.warn("figure-extract[{}] crop failed for block {}: {}",
                examId, match.blockId(), e.toString());
            return false;
        }
        if (crop.isEmpty()) {
            log.info("figure-extract[{}] block {} on page {} rejected by a crop guard",
                examId, match.blockId(), match.pageNumber());
            return false;
        }

        SectionFigure fig = new SectionFigure();
        fig.setBlockId(match.blockId());
        fig.setSource("pdf");
        fig.setPosition(0);
        // The parser's caption for this figure, seeding the field the author can
        // edit. Null when the PDF printed none — captions are optional.
        fig.setCaption(caption);
        // The `auto/` segment keeps extracted images separable from the user's own
        // uploads, so a future re-extract can clear only what it created.
        String path = userId + "/" + examId + "/auto/" + fig.getId() + ".png";
        fig.setStoragePath(path);

        // Store first, then insert. This is the opposite order to a manual upload,
        // which can roll its row back: here an orphaned row would render a broken
        // <img>, while an orphaned object is invisible.
        try {
            storage.store(FIGURE_BUCKET, path, crop.get().png());
        } catch (Exception e) {
            log.warn("figure-extract[{}] store failed for block {}: {}",
                examId, match.blockId(), e.toString());
            return false;
        }
        try {
            figureRepository.save(fig);
        } catch (Exception e) {
            // Most likely the block was cascaded away by a re-parse (block_id is an FK).
            log.warn("figure-extract[{}] row insert failed for block {}: {}",
                examId, match.blockId(), e.toString());
            try {
                storage.delete(FIGURE_BUCKET, path);
            } catch (Exception ignored) {
                // Best-effort cleanup; an unreferenced object harms nothing.
            }
            return false;
        }
        log.info("figure-extract[{}] block {} page {} -> {}x{} px",
            examId, match.blockId(), match.pageNumber(),
            crop.get().width(), crop.get().height());
        return true;
    }

    /** A re-parse landed while we were working: its blocks are not ours to fill. */
    private boolean isStale(UUID examId, OffsetDateTime parsedAt) {
        if (parsedAt == null) return false;
        OffsetDateTime now = examRepository.findById(examId).map(Exam::getParsedAt).orElse(null);
        return now == null || !now.equals(parsedAt);
    }
}
