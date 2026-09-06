package app.parse;

import java.io.ByteArrayOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Page counting gates two things that must not be skipped: the parser's page cap
 * and the {@code exams.page_count} the upload stamps. Both treat an unreadable
 * PDF as "unknown" rather than zero, so the null case matters as much as the
 * happy one.
 */
class PdfPageCounterTest {

    private final PdfPageCounter pageCounter = new PdfPageCounter();

    private static byte[] pdf(int pages) {
        try (PDDocument doc = new PDDocument()) {
            for (int p = 0; p < pages; p++) doc.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void pageCountReportsTheNumberOfPages() {
        assertThat(pageCounter.pageCount(pdf(3))).isEqualTo(3);
    }

    @Test
    void pageCountReturnsNullForUnreadableBytes() {
        assertThat(pageCounter.pageCount("not a pdf".getBytes())).isNull();
    }
}
