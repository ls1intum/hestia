package app.parse.figures;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Optional;

import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The page-space → pixel mapping is the highest-risk arithmetic in the feature:
 * a bug there produces a crop that is somewhere, just not where it should be, and
 * looks plausible enough to ship. So the core test does not check the arithmetic
 * against itself — it renders with PDFBox and verifies the crop actually lands on
 * the ink, for every page rotation and for an offset CropBox.
 */
class PdfFigureCropperTest {

    private final PdfFigureCropper cropper = new PdfFigureCropper();

    /** A solid black rectangle at a known spot in PDF user space. */
    private static final Rectangle2D INK = new Rectangle2D.Double(100, 500, 200, 150);

    private static byte[] pageWithInk(int rotation) {
        return TestPdfs.rotatedPage(rotation, (doc, cs) -> {
            cs.setNonStrokingColor(Color.BLACK);
            cs.addRect(100, 500, 200, 150);
            cs.fill();
        });
    }

    private static double darkFraction(BufferedImage img) {
        long dark = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) & 0xFF) < 128) dark++;
            }
        }
        return (double) dark / ((long) img.getWidth() * img.getHeight());
    }

    private static BufferedImage render(PDDocument doc) throws Exception {
        return new PDFRenderer(doc).renderImageWithDPI(0, PdfFigureCropper.DPI, ImageType.RGB);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 90, 180, 270})
    void cropLandsOnTheInkAtEveryPageRotation(int rotation) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pageWithInk(rotation))) {
            PDPage page = doc.getPage(0);
            BufferedImage rendered = render(doc);

            Optional<PdfFigureCropper.Crop> crop = cropper.crop(rendered, page, INK);

            assertThat(crop).isPresent();
            BufferedImage out = ImageIO.read(new ByteArrayInputStream(crop.get().png()));
            // The 4pt pad adds a thin white border around a fully black rect.
            assertThat(darkFraction(out))
                .as("rotation %d", rotation)
                .isGreaterThan(0.80);
        }
    }

    @Test
    void cropLandsOnTheInkWhenTheCropBoxIsOffsetFromTheOrigin() throws Exception {
        PDRectangle cropBox = new PDRectangle(40, 60, 500, 700); // llx=40, lly=60
        byte[] pdf = TestPdfs.croppedPage(cropBox, (doc, cs) -> {
            cs.setNonStrokingColor(Color.BLACK);
            cs.addRect(100, 500, 200, 150);
            cs.fill();
        });
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            BufferedImage rendered = render(doc);

            Optional<PdfFigureCropper.Crop> crop = cropper.crop(rendered, doc.getPage(0), INK);

            assertThat(crop).isPresent();
            BufferedImage out = ImageIO.read(new ByteArrayInputStream(crop.get().png()));
            assertThat(darkFraction(out)).isGreaterThan(0.80);
        }
    }

    @Test
    void mapsPageSpaceToPixelsRelativeToTheCropBoxOrigin() throws Exception {
        PDRectangle cropBox = new PDRectangle(50, 100, 495, 690);
        try (PDDocument doc = Loader.loadPDF(TestPdfs.croppedPage(cropBox, (d, cs) -> {}))) {
            PDPage page = doc.getPage(0);
            double s = PdfFigureCropper.DPI / 72.0;

            Rectangle px = PdfFigureCropper.toPixels(
                new Rectangle2D.Double(150, 300, 100, 80), page, PdfFigureCropper.DPI);

            assertThat(px.x).isCloseTo((int) ((150 - 50) * s), within(1));
            // y measures down from the CropBox top: 690 - (380 - 100) = 410 points.
            assertThat(px.y).isCloseTo((int) ((690 - (380 - 100)) * s), within(1));
            assertThat(px.width).isCloseTo((int) (100 * s), within(2));
            assertThat(px.height).isCloseTo((int) (80 * s), within(2));
        }
    }

    @Test
    void rejectsABlankRegionSoUndecodableImagesNeverShipAsWhiteBoxes() throws Exception {
        // An empty page: any crop of it is blank, which is what a JPX/JBIG2 image
        // PDFBox cannot decode looks like — it renders nothing rather than throwing.
        try (PDDocument doc = Loader.loadPDF(TestPdfs.page((d, cs) -> {}))) {
            BufferedImage rendered = render(doc);

            assertThat(cropper.crop(rendered, doc.getPage(0), INK)).isEmpty();
        }
    }

    @Test
    void rejectsARegionSmallerThanAFigureCouldBe() throws Exception {
        try (PDDocument doc = Loader.loadPDF(pageWithInk(0))) {
            BufferedImage rendered = render(doc);

            // ~3x3 points — a bullet glyph, not a figure.
            Optional<PdfFigureCropper.Crop> crop = cropper.crop(
                rendered, doc.getPage(0), new Rectangle2D.Double(150, 550, 3, 3));

            assertThat(crop).isEmpty();
        }
    }

    @Test
    void keepsLineArtThatUsesOnlyBlackAndWhite() throws Exception {
        // A schematic is nearly bitonal; a flatness guard set too high would
        // reject exactly the figures this feature exists to extract.
        byte[] pdf = TestPdfs.page((doc, cs) -> {
            cs.setStrokingColor(Color.BLACK);
            cs.setLineWidth(1.5f);
            for (int i = 0; i < 12; i++) {
                cs.moveTo(110, 510 + i * 11);
                cs.lineTo(290, 510 + i * 11);
            }
            cs.stroke();
        });
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            BufferedImage rendered = render(doc);

            assertThat(cropper.crop(rendered, doc.getPage(0), INK)).isPresent();
        }
    }

    @Test
    void padsTheRegionSoHairlineStrokesAreNotClipped() throws Exception {
        try (PDDocument doc = Loader.loadPDF(pageWithInk(0))) {
            PDPage page = doc.getPage(0);

            Rectangle2D padded = PdfFigureCropper.pad(INK, page);

            assertThat(padded.getWidth()).isGreaterThan(INK.getWidth());
            assertThat(padded.contains(INK)).isTrue();
        }
    }

    @Test
    void padStaysInsideTheCropBox() throws Exception {
        PDRectangle cropBox = new PDRectangle(0, 0, 595, 842);
        try (PDDocument doc = Loader.loadPDF(TestPdfs.croppedPage(cropBox, (d, cs) -> {}))) {
            // A region flush against the page edge must not pad outside it.
            Rectangle2D padded = PdfFigureCropper.pad(
                new Rectangle2D.Double(0, 0, 100, 100), doc.getPage(0));

            assertThat(padded.getMinX()).isGreaterThanOrEqualTo(0);
            assertThat(padded.getMinY()).isGreaterThanOrEqualTo(0);
        }
    }
}
