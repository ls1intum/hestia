package app.parse;

import java.io.ByteArrayOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Text extraction is the deterministic core of Fast Mode. These tests pin the
 * behaviour that matters operationally: born-digital PDFs yield paginated text,
 * and scanned/empty PDFs are rejected up-front with an actionable message
 * (rather than silently sending an empty prompt to the LLM).
 */
class PdfTextExtractorTest {

    private final PdfTextExtractor extractor = new PdfTextExtractor();

    /** Build a PDF whose pages carry the given text lines. */
    private static byte[] pdf(int pages, String... lines) {
        try (PDDocument doc = new PDDocument()) {
            for (int p = 0; p < pages; p++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(50, 750);
                    for (String line : lines) {
                        cs.showText(line);
                        cs.newLineAtOffset(0, -15);
                    }
                    cs.endText();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void extractsTextWithPerPageMarkers() {
        // Enough characters to clear the MIN_USABLE_LENGTH (200) gate.
        String filler = "The quick brown fox jumps over the lazy dog. ";
        byte[] bytes = pdf(1, filler, filler, filler, filler, filler, filler);

        String text = extractor.extractText(bytes);

        assertThat(text).contains("----- Page 1 -----");
        assertThat(text).contains("quick brown fox");
    }

    @Test
    void multiPageTextGetsAMarkerPerPage() {
        String filler = "Lorem ipsum dolor sit amet consectetur adipiscing elit. ";
        byte[] bytes = pdf(2, filler, filler, filler, filler);

        String text = extractor.extractText(bytes);

        assertThat(text).contains("----- Page 1 -----").contains("----- Page 2 -----");
    }

    @Test
    void rejectsScannedLikePdfWithNoUsableText() {
        byte[] blank = pdf(1); // page with no text — mimics a scanned image page

        assertThatThrownBy(() -> extractor.extractText(blank))
            .isInstanceOf(PdfTextExtractor.TextExtractionException.class)
            .hasMessageContaining("scanned");
    }

    @Test
    void pageCountReportsTheNumberOfPages() {
        assertThat(extractor.pageCount(pdf(3, "a"))).isEqualTo(3);
    }

    @Test
    void pageCountReturnsNullForUnreadableBytes() {
        assertThat(extractor.pageCount("not a pdf".getBytes())).isNull();
    }

    @Test
    void extractThrowsOnUnreadableBytes() {
        assertThatThrownBy(() -> extractor.extractText("not a pdf".getBytes()))
            .isInstanceOf(PdfTextExtractor.TextExtractionException.class);
    }
}
