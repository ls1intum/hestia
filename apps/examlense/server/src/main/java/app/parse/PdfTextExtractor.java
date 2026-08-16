package app.parse;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/**
 * Extracts plain text from a PDF using Apache PDFBox (PDFBox is what Tika
 * delegates to internally for PDFs). Used by Fast Mode as a cheap alternative
 * to direct-PDF or vision-LLM rasterization for born-digital exams.
 *
 * Produces per-page text with explicit `----- Page N -----` markers so the
 * LLM can populate {@code figures[].page_number} and reason about layout
 * boundaries. Strips NUL bytes (PDFBox emits 0x00 for unmapped glyphs in
 * LaTeX math fonts) since Postgres TEXT columns reject them.
 *
 * Scanned PDFs produce little or no usable text; we reject those up-front
 * with a clear message so the user can retry with Fast Mode off.
 */
@Component
public class PdfTextExtractor {

    private static final int MIN_USABLE_LENGTH = 200;

    public static class TextExtractionException extends RuntimeException {
        public TextExtractionException(String msg) { super(msg); }
        public TextExtractionException(String msg, Throwable cause) { super(msg, cause); }
    }

    /** Page count via PDFBox; returns null if the PDF can't be opened. */
    public Integer pageCount(byte[] pdfBytes) {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            return doc.getNumberOfPages();
        } catch (Exception e) {
            return null;
        }
    }

    public String extractText(byte[] pdfBytes) {
        StringBuilder out = new StringBuilder();
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            int pages = doc.getNumberOfPages();
            for (int i = 1; i <= pages; i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String pageText = sanitize(stripper.getText(doc));
                out.append("----- Page ").append(i).append(" -----\n");
                out.append(pageText);
                if (!pageText.endsWith("\n")) out.append('\n');
            }
        } catch (Exception e) {
            throw new TextExtractionException("Failed to extract text from PDF: " + e.getMessage(), e);
        }
        String text = out.toString().trim();
        if (stripPageMarkers(text).length() < MIN_USABLE_LENGTH) {
            throw new TextExtractionException(
                "PDF contains no readable text — likely a scanned document, "
                    + "pick a vision parser strategy instead.");
        }
        return text;
    }

    /** Remove NUL bytes (Postgres rejects 0x00 in TEXT columns) and other C0 noise. */
    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
    }

    /** Used by the min-length check so a heavily-paginated empty PDF can't pass on marker bulk alone. */
    private static String stripPageMarkers(String s) {
        return s.replaceAll("(?m)^----- Page \\d+ -----\\s*$", "").trim();
    }
}
