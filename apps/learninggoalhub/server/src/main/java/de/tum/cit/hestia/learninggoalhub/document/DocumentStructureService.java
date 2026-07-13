package de.tum.cit.hestia.learninggoalhub.document;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Extracts a document's raw text together with its structural learning units (sessions). The session
 * boundaries are derived <em>deterministically</em> from PDF bookmarks (the table of contents that
 * combined lecture scripts carry) rather than from an LLM — so the same file always splits the same
 * way. Each top-level bookmark becomes one section, spanning a half-open character range of the raw
 * text; chunks of that range are later routed to the section's hierarchy node by offset.
 *
 * <p>A document is treated as a single session (no sections returned) when it is not a PDF, when the
 * PDF has no bookmarks (e.g. a single-lecture slide deck), when its bookmarks are judged to be one
 * lecture's internal outline rather than separate units (see {@link BookmarkRelevanceJudge}), or when
 * parsing fails: in every such case the caller falls back to one session titled by the vision model.
 */
@Service
public class DocumentStructureService {

    private static final Logger log = LoggerFactory.getLogger(DocumentStructureService.class);

    private final DocumentParser textParser;
    private final BookmarkRelevanceJudge bookmarkJudge;

    public DocumentStructureService(DocumentParser textParser, BookmarkRelevanceJudge bookmarkJudge) {
        this.textParser = textParser;
        this.bookmarkJudge = bookmarkJudge;
    }

    /** A document's extracted text and the structural sections detected in it (possibly empty). */
    public record ParsedDocument(String rawText, List<SectionSpan> sections) {
    }

    /** One detected section: a title and the half-open {@code [startOffset, endOffset)} into rawText. */
    public record SectionSpan(String title, int startOffset, int endOffset) {
    }

    public ParsedDocument parse(byte[] bytes, String contentType, String filename) {
        if (isPdf(contentType, filename)) {
            try {
                return parsePdf(bytes, filename);
            } catch (IOException | RuntimeException e) {
                log.warn("PDF structural parse failed for {}, falling back to flat text: {}",
                        filename, e.getMessage());
            }
        }
        // Non-PDF or PDF parse failure: flat text via Tika, no structural sections (single session).
        return new ParsedDocument(sanitize(textParser.parse(new ByteArrayInputStream(bytes))), List.of());
    }

    /**
     * Drops NUL ({@code 0x00}) characters, which PDFBox emits for unmapped glyphs (e.g. LaTeX math
     * fonts) but a Postgres {@code TEXT} column rejects ("invalid byte sequence for encoding UTF8:
     * 0x00"). Applied per page before offsets are measured, so section ranges stay consistent with
     * rawText.
     */
    static String sanitize(String text) {
        if (text.indexOf(0) < 0) {
            return text;
        }
        StringBuilder cleaned = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != 0) {
                cleaned.append(c);
            }
        }
        return cleaned.toString();
    }

    private static boolean isPdf(String contentType, String filename) {
        return (contentType != null && contentType.toLowerCase().contains("pdf"))
                || (filename != null && filename.toLowerCase().endsWith(".pdf"));
    }

    private ParsedDocument parsePdf(byte[] bytes, String filename) throws IOException {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            int pageCount = doc.getNumberOfPages();
            // Extract text page by page, recording the character offset where each page begins, so a
            // bookmark (which resolves to a page) can be mapped to an offset in rawText.
            PDFTextStripper stripper = new PDFTextStripper();
            StringBuilder text = new StringBuilder();
            int[] pageStart = new int[pageCount + 1];
            for (int page = 1; page <= pageCount; page++) {
                pageStart[page - 1] = text.length();
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                text.append(sanitize(stripper.getText(doc)));
            }
            pageStart[pageCount] = text.length();
            String rawText = text.toString();

            PDDocumentOutline outline = doc.getDocumentCatalog().getDocumentOutline();
            if (outline == null) {
                return new ParsedDocument(rawText, List.of());
            }
            List<SectionSpan> sections = sectionsFromOutline(doc, outline, pageStart, rawText.length());
            // The bookmarks may be a single lecture's internal outline (PowerPoint sections/slides)
            // rather than separate course units. Keep them only when judged to be real units; otherwise
            // treat the document as one session (the caller then titles it via the vision model).
            if (!sections.isEmpty()
                    && !bookmarkJudge.shouldSplit(filename, pageCount, titles(sections))) {
                return new ParsedDocument(rawText, List.of());
            }
            return new ParsedDocument(rawText, sections);
        }
    }

    private static List<String> titles(List<SectionSpan> sections) {
        return sections.stream().map(SectionSpan::title).toList();
    }

    /**
     * Builds sections from the top-level bookmarks only (each = one session; sub-bookmarks fold into
     * their parent's range). Bookmarks that don't resolve to a page or have a blank title are skipped.
     * The first section starts at offset 0 so any front matter before the first bookmark is captured;
     * each subsequent section starts where the next bookmark's page begins.
     */
    private List<SectionSpan> sectionsFromOutline(PDDocument doc, PDDocumentOutline outline,
                                                  int[] pageStart, int textLength) {
        record Marker(int offset, String title) {
        }
        List<Marker> markers = new ArrayList<>();
        for (PDOutlineItem item = outline.getFirstChild(); item != null; item = item.getNextSibling()) {
            String title = item.getTitle() == null ? "" : item.getTitle().replaceAll("\\s+", " ").strip();
            if (title.isEmpty()) {
                continue;
            }
            int pageIndex = resolvePageIndex(doc, item);
            if (pageIndex < 0) {
                continue;
            }
            int offset = pageStart[Math.min(pageIndex, pageStart.length - 1)];
            // Skip a bookmark landing on the same page as the previous one (keep the first title) so two
            // top-level entries on one page don't create an empty section.
            if (!markers.isEmpty() && markers.get(markers.size() - 1).offset() == offset) {
                continue;
            }
            markers.add(new Marker(offset, title));
        }
        if (markers.isEmpty()) {
            return List.of();
        }

        List<SectionSpan> sections = new ArrayList<>(markers.size());
        for (int i = 0; i < markers.size(); i++) {
            int start = i == 0 ? 0 : markers.get(i).offset();
            int end = i + 1 < markers.size() ? markers.get(i + 1).offset() : textLength;
            sections.add(new SectionSpan(markers.get(i).title(), start, end));
        }
        return sections;
    }

    private int resolvePageIndex(PDDocument doc, PDOutlineItem item) {
        try {
            PDPage page = item.findDestinationPage(doc);
            return page == null ? -1 : doc.getPages().indexOf(page);
        } catch (IOException e) {
            return -1;
        }
    }
}
