package app.parse.figures;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.junit.jupiter.api.Test;

import java.awt.geom.Rectangle2D;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The engine is the feature's foundation: every downstream rectangle is only as
 * good as the placement it reports here. These tests pin the two coordinate
 * conventions that are easy to get backwards — images need the CTM applied, path
 * callbacks do not — and the form-BBox tightening that makes LaTeX figures work.
 */
class PdfFigureRegionEngineTest {

    private static List<FigureRegion> scan(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return PdfFigureRegionEngine.scan(doc.getPage(0));
        }
    }

    private static List<FigureRegion> of(List<FigureRegion> all, FigureRegion.Kind kind) {
        return all.stream().filter(r -> r.kind() == kind).toList();
    }

    @Test
    void reportsAnImageAtItsPlacementRectNotItsPixelSize() throws Exception {
        // 400x300 px bitmap placed into a 200x150 pt box: the placement wins.
        byte[] pdf = TestPdfs.page((doc, cs) ->
            cs.drawImage(TestPdfs.image(doc, 400, 300), 100, 500, 200, 150));

        List<FigureRegion> rasters = of(scan(pdf), FigureRegion.Kind.RASTER);

        assertThat(rasters).hasSize(1);
        Rectangle2D r = rasters.get(0).rect();
        assertThat(r.getX()).isCloseTo(100, within(0.5));
        assertThat(r.getY()).isCloseTo(500, within(0.5));
        assertThat(r.getWidth()).isCloseTo(200, within(0.5));
        assertThat(r.getHeight()).isCloseTo(150, within(0.5));
    }

    @Test
    void carriesAnXobjectKeySoRepeatedLetterheadsCanBeRecognised() throws Exception {
        byte[] pdf = TestPdfs.page((doc, cs) -> {
            var img = TestPdfs.image(doc, 40, 40);
            cs.drawImage(img, 50, 700, 30, 30);
            cs.drawImage(img, 50, 100, 30, 30);
        });

        List<FigureRegion> rasters = of(scan(pdf), FigureRegion.Kind.RASTER);

        assertThat(rasters).hasSize(2);
        assertThat(rasters.get(0).xobjectKey())
            .isNotNull()
            .isEqualTo(rasters.get(1).xobjectKey());
    }

    @Test
    void tightensAGenerousFormBBoxToWhatWasActuallyDrawn() throws Exception {
        // Declared BBox spans 300x300; the form only paints 120x90 inside it.
        PDRectangle declared = new PDRectangle(0, 0, 300, 300);
        PDRectangle inner = new PDRectangle(20, 30, 120, 90);
        byte[] pdf = TestPdfs.page((doc, cs) -> {
            PDFormXObject form = TestPdfs.form(doc, declared, inner);
            cs.drawForm(form);
        });

        List<FigureRegion> forms = of(scan(pdf), FigureRegion.Kind.FORM);

        assertThat(forms).hasSize(1);
        Rectangle2D r = forms.get(0).rect();
        // The result tracks the drawn content, not the 300x300 declared box. It
        // carries the path's half-line-width inflation, so allow a couple of points.
        assertThat(r).matches(box -> box.contains(inner.getLowerLeftX(), inner.getLowerLeftY(),
            inner.getWidth(), inner.getHeight()), "contains the drawn rect");
        assertThat(r.getWidth()).isCloseTo(120, within(2.5));
        assertThat(r.getHeight()).isCloseTo(90, within(2.5));
    }

    @Test
    void seesAnImageNestedInsideAFormXObject() throws Exception {
        // This is the LaTeX \includegraphics / docx4j-FOP shape. Walking
        // getXObjectNames() on the page would find only the form and miss the
        // image entirely; running the content stream recurses into it.
        PDRectangle bbox = new PDRectangle(120, 430, 250, 190);
        byte[] pdf = TestPdfs.page((doc, cs) -> cs.drawForm(TestPdfs.formWithImage(doc, bbox, 400, 300)));

        List<FigureRegion> all = scan(pdf);

        assertThat(of(all, FigureRegion.Kind.RASTER)).hasSize(1);
        assertThat(of(all, FigureRegion.Kind.FORM)).hasSize(1);
        Rectangle2D form = of(all, FigureRegion.Kind.FORM).get(0).rect();
        assertThat(form.getX()).isCloseTo(120, within(1.0));
        assertThat(form.getWidth()).isCloseTo(250, within(1.0));
        assertThat(form.getHeight()).isCloseTo(190, within(1.0));
    }

    @Test
    void reportsFilledPathsAsVectorCandidates() throws Exception {
        byte[] pdf = TestPdfs.page((doc, cs) -> TestPdfs.filledRect(cs, 80, 400, 200, 120));

        List<FigureRegion> vectors = of(scan(pdf), FigureRegion.Kind.VECTOR);

        assertThat(vectors).hasSize(1);
        Rectangle2D r = vectors.get(0).rect();
        // Inflated by half the line width (+0.25) on each side.
        assertThat(r.getX()).isCloseTo(80, within(1.5));
        assertThat(r.getWidth()).isCloseTo(200, within(3.0));
    }

    @Test
    void aPathUsedOnlyForClippingPaintsNothing() throws Exception {
        byte[] pdf = TestPdfs.page((doc, cs) -> {
            cs.addRect(50, 50, 400, 400);
            cs.clip();
            // no fill/stroke of that path — it must not become a region
        });

        assertThat(of(scan(pdf), FigureRegion.Kind.VECTOR)).isEmpty();
    }

    @Test
    void recordsGlyphBoxesInTheSamePageSpaceAsArtwork() throws Exception {
        byte[] pdf = TestPdfs.page((doc, cs) -> TestPdfs.text(cs, 72, 760, "Aufgabe 1"));

        List<FigureRegion> glyphs = of(scan(pdf), FigureRegion.Kind.GLYPH);

        assertThat(glyphs).isNotEmpty();
        Rectangle2D first = glyphs.get(0).rect();
        assertThat(first.getX()).isCloseTo(72, within(2.0));
        // Baseline 760, minus a descender allowance, and ~11pt tall.
        assertThat(first.getY()).isBetween(752.0, 760.5);
        assertThat(first.getHeight()).isCloseTo(11, within(2.0));
    }

    @Test
    void separatesArtworkFromText() throws Exception {
        byte[] pdf = TestPdfs.page((doc, cs) -> {
            cs.drawImage(TestPdfs.image(doc, 200, 200), 100, 500, 180, 180);
            TestPdfs.text(cs, 72, 300, "Berechnen Sie den Flaecheninhalt.");
        });

        List<FigureRegion> all = scan(pdf);

        assertThat(all.stream().filter(FigureRegion::isArtwork)).hasSize(1);
        assertThat(of(all, FigureRegion.Kind.GLYPH)).isNotEmpty();
    }
}
