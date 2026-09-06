package app.parse;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Component;

/**
 * Reads a PDF's page count with Apache PDFBox.
 *
 * <p>Used to stamp {@code exams.page_count} at upload time and to enforce the
 * parser's page cap before anything is sent to a model.
 */
@Component
public class PdfPageCounter {

    /** Page count via PDFBox; returns null if the PDF can't be opened. */
    public Integer pageCount(byte[] pdfBytes) {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            return doc.getNumberOfPages();
        } catch (Exception e) {
            return null;
        }
    }
}
