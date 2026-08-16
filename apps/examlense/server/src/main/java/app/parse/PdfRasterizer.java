package app.parse;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders a PDF to one PNG per page using Apache PDFBox.
 *
 * Replaces the Deno `pdf-rasterize.ts` helper. We use PDFBox's renderer at a
 * fixed DPI (default 150 to match the edge function) and encode each page as
 * a PNG bytestream that the OpenAI-compatible vision API can ingest via
 * `image_url` parts.
 */
@Component
public class PdfRasterizer {

    public static class RasterizeException extends RuntimeException {
        public RasterizeException(String msg, Throwable cause) { super(msg, cause); }
    }

    public List<byte[]> rasterize(byte[] pdfBytes, float dpi) {
        List<byte[]> pages = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int n = doc.getNumberOfPages();
            for (int i = 0; i < n; i++) {
                BufferedImage img = renderer.renderImageWithDPI(i, dpi, ImageType.RGB);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ImageIO.write(img, "png", out);
                pages.add(out.toByteArray());
            }
            return pages;
        } catch (Exception e) {
            throw new RasterizeException("Failed to rasterize PDF: " + e.getMessage(), e);
        }
    }
}