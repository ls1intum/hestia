package de.tum.cit.hestia.learninggoalhub.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.hestia.learninggoalhub.document.DocumentStructureService.ParsedDocument;
import de.tum.cit.hestia.learninggoalhub.document.DocumentStructureService.SectionSpan;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.junit.jupiter.api.Test;

class DocumentStructureServiceTest {

    private final BookmarkRelevanceJudge judge = mock(BookmarkRelevanceJudge.class);
    private final DocumentStructureService service = new DocumentStructureService(new DocumentParser(), judge);

    @Test
    void splitsPdfIntoSectionsByTopLevelBookmarks() throws Exception {
        // 3 pages, two top-level bookmarks: chapter one on page 0, chapter two on page 1 (spanning
        // pages 1-2). Each page carries a unique word so we can check the ranges contain the right text.
        // The judge is the keep/discard verdict; here it keeps so we exercise the deterministic ranges.
        when(judge.shouldSplit(any(), anyInt(), any())).thenReturn(true);
        byte[] pdf = pdf(List.of("alpha", "bravo", "charlie"), List.of(
                new Bookmark("Chapter One", 0),
                new Bookmark("Chapter Two", 1)));

        ParsedDocument parsed = service.parse(pdf, "application/pdf", "combined.pdf");
        List<SectionSpan> sections = parsed.sections();

        assertThat(sections).hasSize(2);
        assertThat(sections).extracting(SectionSpan::title)
                .containsExactly("Chapter One", "Chapter Two");
        // Contiguous, gap-free cover of the whole text starting at 0.
        assertThat(sections.get(0).startOffset()).isZero();
        assertThat(sections.get(0).endOffset()).isEqualTo(sections.get(1).startOffset());
        assertThat(sections.get(1).endOffset()).isEqualTo(parsed.rawText().length());
        // The first section covers only page 0, the second the remaining pages.
        assertThat(parsed.rawText().substring(sections.get(0).startOffset(), sections.get(0).endOffset()))
                .contains("alpha").doesNotContain("bravo", "charlie");
        assertThat(parsed.rawText().substring(sections.get(1).startOffset(), sections.get(1).endOffset()))
                .contains("bravo", "charlie").doesNotContain("alpha");
    }

    @Test
    void discardsBookmarksWhenJudgedToBeOneLecturesOutline() throws Exception {
        // Same bookmarked PDF, but the judge rules the bookmarks are a single lecture's internal
        // outline: no sections are returned, so the caller treats it as one session.
        when(judge.shouldSplit(any(), anyInt(), any())).thenReturn(false);
        byte[] pdf = pdf(List.of("alpha", "bravo", "charlie"), List.of(
                new Bookmark("Intro", 0),
                new Bookmark("Summary", 1)));

        ParsedDocument parsed = service.parse(pdf, "application/pdf", "W06 Continuous Delivery.pdf");

        assertThat(parsed.sections()).isEmpty();
        assertThat(parsed.rawText()).contains("alpha", "bravo", "charlie");
    }

    @Test
    void pdfWithoutBookmarksHasNoSections() throws Exception {
        byte[] pdf = pdf(List.of("solo lecture content"), List.of());

        ParsedDocument parsed = service.parse(pdf, "application/pdf", "lecture.pdf");

        assertThat(parsed.sections()).isEmpty();
        assertThat(parsed.rawText()).contains("solo lecture content");
    }

    @Test
    void sanitizeDropsNulCharactersPostgresRejects() {
        // PDFBox emits NUL (0x00) for unmapped glyphs; a Postgres TEXT column rejects it. The NUL is
        // built from a char code so no control character sits in the source.
        String withNul = "ML" + (char) 0 + "Basics" + (char) 0;

        String cleaned = DocumentStructureService.sanitize(withNul);

        assertThat(cleaned).isEqualTo("MLBasics");
        assertThat(cleaned.indexOf(0)).isNegative();
        // A string without NUL is returned unchanged (same instance, fast path).
        assertThat(DocumentStructureService.sanitize("clean")).isEqualTo("clean");
    }

    @Test
    void nonPdfIsParsedAsFlatTextWithNoSections() {
        byte[] txt = "Just some plain notes about testing.".getBytes();

        ParsedDocument parsed = service.parse(txt, "text/plain", "notes.txt");

        assertThat(parsed.sections()).isEmpty();
        assertThat(parsed.rawText()).contains("Just some plain notes about testing.");
    }

    private record Bookmark(String title, int pageIndex) {
    }

    private static byte[] pdf(List<String> pageTexts, List<Bookmark> bookmarks) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            List<PDPage> pages = new java.util.ArrayList<>();
            for (String text : pageTexts) {
                PDPage page = new PDPage();
                doc.addPage(page);
                pages.add(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(FontName.HELVETICA), 12);
                    cs.newLineAtOffset(50, 700);
                    cs.showText(text);
                    cs.endText();
                }
            }
            if (!bookmarks.isEmpty()) {
                PDDocumentOutline outline = new PDDocumentOutline();
                doc.getDocumentCatalog().setDocumentOutline(outline);
                for (Bookmark b : bookmarks) {
                    PDOutlineItem item = new PDOutlineItem();
                    item.setTitle(b.title());
                    item.setDestination(pages.get(b.pageIndex()));
                    outline.addLast(item);
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
