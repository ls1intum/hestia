package de.tum.cit.hestia.learninggoalhub.document;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MimeTypeUtils;

/** Describes eligible text-poor PDF pages without changing the document's extracted text. */
@Service
public class PageDescriptionService {

    private static final Logger log = LoggerFactory.getLogger(PageDescriptionService.class);

    // Fixed before any evaluation run; keep these thresholds stable and out of configuration.
    static final int MIN_PAGE_CHARS = 200;
    static final double MIN_ALNUM_RATIO = 0.40;
    // Fixed before any evaluation run; keep the VLM request shape stable.
    static final int BATCH_SIZE = 8;
    /**
     * Latency does not depend on this: 72 dpi cost the same ~11.5 s per page as 120. A later run at
     * 120 dpi with this prompt produced descriptions as short and as identifier-poor as the 72 dpi
     * one (189 vs 186 chars), so resolution buys nothing measurable either way — the richer earlier
     * descriptions came from the older prompt. Kept at 120 as the higher-fidelity input.
     */
    static final int RENDER_DPI = 120;

    static final String PROMPT = """
            These images are pages of university course material (lecture slides, an exercise sheet or an exam), and the page numbers are given in the same order as the images. For each page, return 1-3 sentences describing the figure or diagram content and what it teaches — factual, with no meta-commentary.

            Also set teachesContent for each page. It is false when the page carries no subject matter of its own — a title page, a section header, a page that only announces a topic, a pure summary or agenda, an author/affiliation page, or a blank or answer-box page. It is true when the page shows something a student could learn from, such as a diagram, circuit, table, plot, worked example or task.

            Reply as a JSON array of {"page": n, "description": "...", "teachesContent": true|false} using the given page numbers.

            Requested page numbers: %s
            """;

    private final ChatClient chatClient;
    private final PageDescriptionRepository pageDescriptionRepository;
    private final String visionModel;

    public PageDescriptionService(ChatClient.Builder chatClientBuilder,
                                  PageDescriptionRepository pageDescriptionRepository,
                                  @Value("${hestia.figures.vision-model:qwen3.6-35b-a3b}") String visionModel) {
        this.chatClient = chatClientBuilder.build();
        this.pageDescriptionRepository = pageDescriptionRepository;
        this.visionModel = visionModel;
    }

    /**
     * Describes each eligible page not already stored for the document. A failed batch is isolated
     * so later pages still get a chance to be described. Runs in its own transaction so the VLM
     * work commits per document — the extraction run wrapping this is one long transaction, and a
     * failure in a later pipeline phase must not roll the descriptions back (they are what makes
     * re-runs free).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void describeEligiblePages(Document document, byte[] pdfBytes) {
        try {
            describeEligiblePagesInternal(document, pdfBytes);
        } catch (RuntimeException e) {
            log.warn("Figure description preparation failed: {}", e.getMessage());
        }
    }

    private void describeEligiblePagesInternal(Document document, byte[] pdfBytes) {
        if (document == null || pdfBytes == null || !isPdf(document)
                || document.getRawText() == null || document.getRawText().isBlank()) {
            return;
        }
        int[] pageOffsets = document.getPageOffsets();
        if (pageOffsets == null || pageOffsets.length < 2) {
            return;
        }

        Set<Integer> describedPages = new HashSet<>(pageDescriptionRepository.findByDocumentId(document.getId())
                .stream().map(PageDescription::getPage).toList());
        List<Integer> eligiblePages = new ArrayList<>();
        String rawText = document.getRawText();
        for (int page = 1; page < pageOffsets.length; page++) {
            if (describedPages.contains(page)) {
                continue;
            }
            int start = Math.max(0, Math.min(pageOffsets[page - 1], rawText.length()));
            int end = Math.max(start, Math.min(pageOffsets[page], rawText.length()));
            if (eligible(rawText.substring(start, end))) {
                eligiblePages.add(page);
            }
        }
        if (eligiblePages.isEmpty()) {
            return;
        }

        try (PDDocument pdf = Loader.loadPDF(pdfBytes)) {
            PDFRenderer renderer = new PDFRenderer(pdf);
            for (int start = 0; start < eligiblePages.size(); start += BATCH_SIZE) {
                List<Integer> batch = eligiblePages.subList(start, Math.min(start + BATCH_SIZE, eligiblePages.size()));
                try {
                    List<Media> media = renderPages(renderer, batch);
                    List<PageReply> replies = chatClient.prompt()
                            .options(ChatOptions.builder().model(visionModel).build())
                            .user(u -> u.text(PROMPT.formatted(batch.stream()
                                    .map(String::valueOf).collect(java.util.stream.Collectors.joining(", "))))
                                    .media(media.toArray(Media[]::new)))
                            .call()
                            .entity(new ParameterizedTypeReference<List<PageReply>>() {});
                    persistReplies(document, batch, replies);
                } catch (IOException | RuntimeException e) {
                    log.warn("VLM figure description failed for document {} pages {}-{}: {}",
                            document.getId(), batch.getFirst(), batch.getLast(), e.getMessage());
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Could not render pages for figure descriptions in document {}: {}",
                    document.getId(), e.getMessage());
        }
    }

    static boolean eligible(String pageText) {
        if (pageText.length() < MIN_PAGE_CHARS) {
            return true;
        }
        return alnumRatio(pageText) < MIN_ALNUM_RATIO;
    }

    static double alnumRatio(String pageText) {
        int nonWhitespace = 0;
        int alphanumeric = 0;
        for (int offset = 0; offset < pageText.length();) {
            int codePoint = pageText.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (!Character.isWhitespace(codePoint)) {
                nonWhitespace++;
                if (Character.isLetterOrDigit(codePoint)) {
                    alphanumeric++;
                }
            }
        }
        return nonWhitespace == 0 ? 0.0 : (double) alphanumeric / nonWhitespace;
    }

    private List<Media> renderPages(PDFRenderer renderer, List<Integer> pages) throws IOException {
        List<Media> media = new ArrayList<>(pages.size());
        for (int page : pages) {
            BufferedImage image = renderer.renderImageWithDPI(page - 1, RENDER_DPI);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(image, "png", png);
            media.add(new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(png.toByteArray())));
        }
        return media;
    }

    private void persistReplies(Document document, List<Integer> batch, List<PageReply> replies) {
        if (replies == null || replies.isEmpty()) {
            return;
        }
        Set<Integer> requested = Set.copyOf(batch);
        Map<Integer, PageReply> accepted = new LinkedHashMap<>();
        for (PageReply reply : replies) {
            if (reply == null || reply.page() == null || !requested.contains(reply.page())
                    || reply.description() == null || reply.description().isBlank()) {
                continue;
            }
            accepted.put(reply.page(), reply);
        }
        for (Map.Entry<Integer, PageReply> entry : accepted.entrySet()) {
            PageReply reply = entry.getValue();
            pageDescriptionRepository.save(new PageDescription(
                    document, entry.getKey(), reply.description().strip(), visionModel,
                    !Boolean.FALSE.equals(reply.teachesContent())));
        }
    }

    private static boolean isPdf(Document document) {
        return (document.getContentType() != null
                && document.getContentType().toLowerCase().contains("pdf"))
                || (document.getFilename() != null
                && document.getFilename().toLowerCase().endsWith(".pdf"));
    }

    public record FigureDescription(int page, String description) {
    }

    /** A missing teachesContent is treated as true so a terse model reply still yields evidence. */
    public record PageReply(Integer page, String description, Boolean teachesContent) {
    }
}
