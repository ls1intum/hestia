package app.parse.figures;

import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Every guard here exists to keep a wrong image out of an exam. The bias the
 * tests encode is deliberate and asymmetric: rejecting a real figure costs the
 * user one screenshot, which is what they do today anyway, while accepting a
 * wrong one is a silent data-quality bug nobody will notice.
 */
class FigureRegionDetectorTest {

    private final FigureRegionDetector detector = new FigureRegionDetector();
    private final PDPage page = new PDPage(PDRectangle.A4);

    private static FigureRegion raster(double x, double y, double w, double h) {
        return new FigureRegion(new Rectangle2D.Double(x, y, w, h), FigureRegion.Kind.RASTER);
    }

    private static FigureRegion form(double x, double y, double w, double h) {
        return new FigureRegion(new Rectangle2D.Double(x, y, w, h), FigureRegion.Kind.FORM);
    }

    /** {@code n} glyph boxes marching down a column, as body text would. */
    private static List<FigureRegion> glyphs(int n, double x, double topY) {
        List<FigureRegion> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new FigureRegion(
                new Rectangle2D.Double(x + (i % 20) * 6, topY - (i / 20) * 14, 5, 10),
                FigureRegion.Kind.GLYPH));
        }
        return out;
    }

    @Test
    void mergesTiledImageStripsIntoOneFigure() {
        // A generator sliced one 200x180 image into six 30pt bands. Each band is
        // 200x30 -> aspect 6.7, under the limit, but only 6000pt2 and 30pt tall,
        // so every band would fail MIN_SIDE if filtering ran before merging.
        List<FigureRegion> regions = new ArrayList<>();
        for (int i = 0; i < 6; i++) regions.add(raster(100, 500 + i * 30, 200, 30));

        List<Rectangle2D> found = detector.detect(page, regions);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getHeight()).isCloseTo(180, within(0.5));
        assertThat(found.get(0).getWidth()).isCloseTo(200, within(0.5));
    }

    @Test
    void countsAFormAndTheImageInsideItOnce() {
        List<Rectangle2D> found = detector.detect(page, List.of(
            form(100, 500, 200, 150),
            raster(105, 505, 190, 140)));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getWidth()).isCloseTo(200, within(0.5));
    }

    @Test
    void rejectsAFullPageScan() {
        List<Rectangle2D> found = detector.detect(page,
            List.of(raster(0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight())));

        assertThat(found).isEmpty();
    }

    @Test
    void rejectsALogoInTheHeaderBand() {
        double top = PDRectangle.A4.getHeight();
        List<Rectangle2D> found = detector.detect(page, List.of(raster(60, top - 45, 90, 40)));

        assertThat(found).isEmpty();
    }

    @Test
    void rejectsAHorizontalRule() {
        List<Rectangle2D> found = detector.detect(page, List.of(raster(60, 700, 480, 3)));

        assertThat(found).isEmpty();
    }

    @Test
    void rejectsAnIconTooSmallToBeAFigure() {
        List<Rectangle2D> found = detector.detect(page, List.of(raster(100, 400, 20, 20)));

        assertThat(found).isEmpty();
    }

    @Test
    void rejectsAFormThatMerelyWrapsTheBodyText() {
        // docx4j/FOP and some LaTeX drivers wrap the whole page body in a form.
        List<FigureRegion> regions = new ArrayList<>(glyphs(120, 72, 760));
        regions.add(form(60, 80, 470, 700));

        assertThat(detector.detect(page, regions)).isEmpty();
    }

    @Test
    void keepsAFigureThatDominatesAPageWithLittleText() {
        // The wrapper ratio is meaningless when a page has only a caption on it:
        // the figure legitimately contains most of the page's few glyphs.
        List<FigureRegion> regions = new ArrayList<>(glyphs(12, 100, 480));
        regions.add(form(90, 460, 300, 250));

        assertThat(detector.detect(page, regions)).hasSize(1);
    }

    @Test
    void ordersFiguresTopToBottomThenLeftToRight() {
        List<Rectangle2D> found = detector.detect(page, List.of(
            raster(300, 300, 120, 120),   // lower right
            raster(80, 600, 120, 120),    // upper left
            raster(300, 600, 120, 120),   // upper right
            raster(80, 300, 120, 120)));  // lower left

        assertThat(found).hasSize(4);
        assertThat(found.get(0).getMinY()).isCloseTo(600, within(0.5));
        assertThat(found.get(0).getMinX()).isCloseTo(80, within(0.5));
        assertThat(found.get(1).getMinX()).isCloseTo(300, within(0.5));
        assertThat(found.get(3).getMinY()).isCloseTo(300, within(0.5));
        assertThat(found.get(3).getMinX()).isCloseTo(300, within(0.5));
    }

    @Test
    void dropsArtworkRepeatedAtTheSameSpotOnEveryPage() {
        Map<Integer, List<Rectangle2D>> byPage = new LinkedHashMap<>();
        for (int p = 1; p <= 4; p++) {
            List<Rectangle2D> onPage = new ArrayList<>();
            onPage.add(new Rectangle2D.Double(60, 740, 90, 45));   // letterhead
            if (p == 2) onPage.add(new Rectangle2D.Double(120, 400, 200, 160)); // real figure
            byPage.put(p, onPage);
        }

        FigureRegionDetector.demoteRepeated(byPage);

        assertThat(byPage.get(1)).isEmpty();
        assertThat(byPage.get(2)).hasSize(1);
        assertThat(byPage.get(2).get(0).getWidth()).isCloseTo(200, within(0.5));
    }

    @Test
    void keepsArtworkThatOnlyAppearsOnTwoPages() {
        Map<Integer, List<Rectangle2D>> byPage = new LinkedHashMap<>();
        byPage.put(1, new ArrayList<>(List.of(new Rectangle2D.Double(60, 400, 200, 160))));
        byPage.put(2, new ArrayList<>(List.of(new Rectangle2D.Double(60, 400, 200, 160))));
        byPage.put(3, new ArrayList<>());

        FigureRegionDetector.demoteRepeated(byPage);

        assertThat(byPage.get(1)).hasSize(1);
        assertThat(byPage.get(2)).hasSize(1);
    }

    @Test
    void ignoresPathsAndGlyphsAsFigureCandidates() {
        List<FigureRegion> regions = new ArrayList<>(glyphs(60, 72, 700));
        regions.add(new FigureRegion(
            new Rectangle2D.Double(100, 300, 200, 150), FigureRegion.Kind.VECTOR));

        assertThat(detector.detect(page, regions)).isEmpty();
    }
}
