package app.flow;

import java.io.ByteArrayOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/** Synthetic fixtures, generated in the test so the repo ships no documents. */
final class FlowFixtures {

    private FlowFixtures() {}

    /** A one-page PDF with a few lines of made-up exam text. */
    static byte[] tinyExamPdf() {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Synthetic Flow Exam");
                cs.newLineAtOffset(0, -20);
                cs.showText("1. Which keyword declares a constant in Java?");
                cs.newLineAtOffset(0, -20);
                cs.showText("2. Which of these are HTTP methods?");
                cs.newLineAtOffset(0, -20);
                cs.showText("3. Explain what an HTTP status code is.");
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
