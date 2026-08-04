package de.tum.cit.hestia.learninggoalhub.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class HighlightGeometryServiceTest {

    private final HighlightGeometryService service = new HighlightGeometryService();

    @Test
    void mapsMultiLineRangeToSeparateRectsAndKeepsRotatedPageInPdfSpace() throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage normal = new PDPage(new PDRectangle(300, 400));
            document.addPage(normal);
            writeLines(document, normal, 40, 330, "first line", "second line");

            String normalText = pageText(document, 1);
            List<HighlightRect> normalRects = service.findHighlightRects(
                    document, 1, normalText.indexOf("first"), normalText.indexOf("second line") + "second line".length());

            assertThat(normalRects).hasSize(2);
            assertThat(normalRects.get(0).x()).isBetween(38.0, 45.0);
            assertThat(normalRects.get(1).x()).isBetween(38.0, 45.0);
            assertThat(normalRects.get(0).y()).isGreaterThan(normalRects.get(1).y());

            PDPage rotated = new PDPage(new PDRectangle(300, 400));
            rotated.setRotation(90);
            document.addPage(rotated);
            writeLines(document, rotated, 40, 300, "rotated page");

            String rotatedText = pageText(document, 2);
            List<HighlightRect> rotatedRects = service.findHighlightRects(
                    document, 2, rotatedText.indexOf("rotated"), rotatedText.indexOf("rotated page") + "rotated page".length());

            assertThat(rotatedRects).hasSize(1);
            HighlightRect rotatedRect = rotatedRects.get(0);
            assertThat(rotatedRect.x()).isBetween(38.0, 45.0);
            assertThat(rotatedRect.y()).isBetween(295.0, 315.0);
            assertThat(rotatedRect.width()).isPositive();
            assertThat(rotatedRect.height()).isPositive();
        }
    }

    private static void writeLines(PDDocument document, PDPage page, float x, float y,
                                   String... lines) throws Exception {
        try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
            stream.beginText();
            stream.setFont(new PDType1Font(FontName.HELVETICA), 12);
            stream.newLineAtOffset(x, y);
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) {
                    stream.newLineAtOffset(0, -30);
                }
                stream.showText(lines[i]);
            }
            stream.endText();
        }
    }

    private static String pageText(PDDocument document, int pageNumber) throws Exception {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(pageNumber);
        stripper.setEndPage(pageNumber);
        return stripper.getText(document);
    }
}
