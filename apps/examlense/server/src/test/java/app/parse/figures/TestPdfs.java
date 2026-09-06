package app.parse.figures;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDFormContentStream;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

/**
 * Builds synthetic PDFs at known geometry for the figure-extraction tests.
 *
 * <p>Fixtures are generated rather than committed: the repo ships no PDFs, and
 * AGENTS.md rules out copyrighted material. Generating them also keeps the exact
 * rectangle a test asserts against visible in the test source.
 */
final class TestPdfs {

    static final float PAGE_W = PDRectangle.A4.getWidth();   // 595.28
    static final float PAGE_H = PDRectangle.A4.getHeight();  // 841.89

    private TestPdfs() {}

    interface Draw {
        void on(PDDocument doc, PDPageContentStream cs) throws Exception;
    }

    /** One A4 page with whatever {@code draw} paints on it. */
    static byte[] page(Draw draw) {
        return pages(1, draw);
    }

    /** One A4 page carrying {@code /Rotate rotation}. */
    static byte[] rotatedPage(int rotation, Draw draw) {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            page.setRotation(rotation);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                draw.on(doc, cs);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** One page whose CropBox is inset from the MediaBox, so the origin is not (0,0). */
    static byte[] croppedPage(PDRectangle cropBox, Draw draw) {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            page.setCropBox(cropBox);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                draw.on(doc, cs);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static byte[] pages(int count, Draw draw) {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < count; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    draw.on(doc, cs);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** A recognisable non-uniform bitmap — flat fills trip the blankness guards. */
    static BufferedImage bitmap(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(Color.BLACK);
        for (int i = 0; i < w; i += 4) g.drawLine(i, 0, i, h);
        g.setColor(Color.RED);
        g.fillOval(w / 4, h / 4, w / 2, h / 2);
        g.dispose();
        return img;
    }

    static PDImageXObject image(PDDocument doc, int w, int h) throws Exception {
        return LosslessFactory.createFromImage(doc, bitmap(w, h));
    }

    /**
     * A Form XObject declaring {@code bbox} that paints a filled rect at
     * {@code inner} — the shape of a {@code \includegraphics} inclusion.
     */
    static PDFormXObject form(PDDocument doc, PDRectangle bbox, PDRectangle inner) throws Exception {
        PDFormXObject form = new PDFormXObject(doc);
        form.setBBox(bbox);
        form.setResources(new PDResources());
        try (PDFormContentStream fcs = new PDFormContentStream(form)) {
            fcs.setNonStrokingColor(Color.BLUE);
            fcs.addRect(inner.getLowerLeftX(), inner.getLowerLeftY(),
                inner.getWidth(), inner.getHeight());
            fcs.fill();
        }
        return form;
    }

    /**
     * A Form XObject wrapping a raster image — the shape LaTeX emits for
     * {@code \includegraphics{fig.pdf}} and docx4j/FOP for a pasted picture.
     */
    static PDFormXObject formWithImage(PDDocument doc, PDRectangle bbox, int px, int py)
        throws Exception {
        PDFormXObject form = new PDFormXObject(doc);
        form.setBBox(bbox);
        PDResources res = new PDResources();
        form.setResources(res);
        PDImageXObject img = image(doc, px, py);
        try (PDFormContentStream fcs = new PDFormContentStream(form)) {
            fcs.drawImage(img, bbox.getLowerLeftX(), bbox.getLowerLeftY(),
                bbox.getWidth(), bbox.getHeight());
        }
        return form;
    }

    static void text(PDPageContentStream cs, float x, float y, String... lines) throws Exception {
        cs.beginText();
        cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
        cs.newLineAtOffset(x, y);
        for (String line : lines) {
            cs.showText(line);
            cs.newLineAtOffset(0, -14);
        }
        cs.endText();
    }

    static void filledRect(PDPageContentStream cs, float x, float y, float w, float h)
        throws Exception {
        cs.setNonStrokingColor(Color.DARK_GRAY);
        cs.addRect(x, y, w, h);
        cs.fill();
    }
}
