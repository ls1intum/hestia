package de.tum.cit.hestia.learninggoalhub.document;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

/**
 * Names a single-session PDF (a slide deck or exercise that carries no bookmarks) by showing its
 * first few rendered pages to a vision model. Flat text extraction loses the visual hierarchy that
 * tells a course banner ("Introduction to Machine Learning", repeated on every slide) apart from
 * the actual session topic, so a text LLM tends to grab the banner or the wrong line. A VLM that
 * <em>sees</em> the slide reads the layout (font size, position) and returns the specific topic.
 *
 * <p>Only the title is VLM-derived: a bookmark-less document is always exactly one session spanning
 * its whole text, so the structural boundaries stay deterministic. On any failure (not a PDF, render
 * error, model unavailable, blank reply) this returns {@code null} and the caller falls back to the
 * filename.
 */
@Service
public class DocumentTitleService {

    private static final Logger log = LoggerFactory.getLogger(DocumentTitleService.class);

    /** Title slides sit at the front; three pages is enough context without inflating the payload. */
    private static final int MAX_PAGES = 3;
    /** Rendering DPI: high enough for the model to read slide text, low enough to keep PNGs small. */
    private static final int RENDER_DPI = 120;
    private static final int MAX_TITLE_LENGTH = 200;

    static final String PROMPT = """
            These are the first pages of ONE lecture or exercise from a university course. Slide \
            decks repeat the COURSE name as a banner (often top and small, e.g. "Introduction to \
            Machine Learning") on every slide. I do NOT want the course name. I want the title of \
            THIS specific session — the most prominent topic heading (e.g. "What is Machine \
            Learning?", "In a Nutshell", "Linear Regression", "Exercise 3: Gradient Descent"). \
            Use the visual layout (font size, position) to tell the session title apart from the \
            course banner and from body text. Exclude author, university, date and page numbers. \
            Reply with ONLY the title, nothing else.""";

    private final ChatClient chatClient;
    private final String visionModel;
    private final boolean enabled;

    public DocumentTitleService(ChatClient.Builder chatClientBuilder,
                                @Value("${hestia.title.vision-model:qwen3.6-35b-a3b}") String visionModel,
                                @Value("${hestia.title.enabled:true}") boolean enabled) {
        this.chatClient = chatClientBuilder.build();
        this.visionModel = visionModel;
        this.enabled = enabled;
    }

    /**
     * Derives a session title from a PDF's first pages, or returns {@code null} if VLM titling is
     * disabled, the document is not a PDF, or the vision model cannot be reached / returns nothing
     * usable. A {@code null} makes the caller fall back to the filename.
     */
    public String deriveTitle(byte[] bytes, String contentType, String filename) {
        if (!enabled || !isPdf(contentType, filename)) {
            return null;
        }
        try {
            List<Media> pages = renderFirstPages(bytes);
            if (pages.isEmpty()) {
                return null;
            }
            String reply = chatClient.prompt()
                    .options(ChatOptions.builder().model(visionModel).build())
                    .user(u -> u.text(PROMPT).media(pages.toArray(Media[]::new)))
                    .call()
                    .content();
            return clean(reply);
        } catch (IOException | RuntimeException e) {
            log.warn("VLM title derivation failed for {}, falling back to filename: {}",
                    filename, e.getMessage());
            return null;
        }
    }

    private List<Media> renderFirstPages(byte[] bytes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int pages = Math.min(MAX_PAGES, doc.getNumberOfPages());
            List<Media> media = new ArrayList<>(pages);
            for (int i = 0; i < pages; i++) {
                BufferedImage image = renderer.renderImageWithDPI(i, RENDER_DPI);
                ByteArrayOutputStream png = new ByteArrayOutputStream();
                ImageIO.write(image, "png", png);
                media.add(new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(png.toByteArray())));
            }
            return media;
        }
    }

    /**
     * Normalises the model reply: strips surrounding quotes/backticks, collapses whitespace (the
     * model may answer over two lines, e.g. a section label and a topic), and caps the length.
     * Returns {@code null} when nothing usable remains.
     */
    static String clean(String reply) {
        if (reply == null) {
            return null;
        }
        String title = reply.replaceAll("^[\"'`\\s]+|[\"'`\\s]+$", "").replaceAll("\\s+", " ").strip();
        if (title.isEmpty()) {
            return null;
        }
        return title.length() > MAX_TITLE_LENGTH ? title.substring(0, MAX_TITLE_LENGTH).strip() : title;
    }

    private static boolean isPdf(String contentType, String filename) {
        return (contentType != null && contentType.toLowerCase().contains("pdf"))
                || (filename != null && filename.toLowerCase().endsWith(".pdf"));
    }
}
