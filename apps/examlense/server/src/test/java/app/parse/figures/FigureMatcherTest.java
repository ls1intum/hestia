package app.parse.figures;

import org.junit.jupiter.api.Test;

import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Matching is where a plausible-but-wrong image gets created, so these tests are
 * mostly about the cases where the matcher must decline: a page it cannot rank,
 * a block with no page number, more blocks than the geometry found.
 */
class FigureMatcherTest {

    private final FigureMatcher matcher = new FigureMatcher();

    private static FigurePlacement block(Integer page, String label, int order) {
        return new FigurePlacement(UUID.randomUUID(), page, label, null, order);
    }

    private static Rectangle2D rect(double x, double y, double w, double h) {
        return new Rectangle2D.Double(x, y, w, h);
    }

    /** Glyph boxes spelling {@code text} on one baseline, as the engine records them. */
    private static List<FigureRegion> line(String text, double x, double y) {
        List<FigureRegion> out = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            out.add(new FigureRegion(new Rectangle2D.Double(x + i * 6, y, 5.5, 10),
                FigureRegion.Kind.GLYPH, null, String.valueOf(text.charAt(i))));
        }
        return out;
    }

    private static FigureMatcher.PageContent page(List<Rectangle2D> regions, List<FigureRegion> glyphs) {
        return new FigureMatcher.PageContent(regions, glyphs);
    }

    @Test
    void zipsOneRegionToOneBlockInReadingOrder() {
        FigurePlacement top = block(2, null, 0);
        FigurePlacement bottom = block(2, null, 1);

        List<FigureMatcher.Match> matches = matcher.match(List.of(top, bottom),
            Map.of(2, page(List.of(rect(100, 600, 200, 150), rect(100, 300, 200, 150)), List.of())));

        assertThat(matches).hasSize(2);
        assertThat(matches.get(0).blockId()).isEqualTo(top.blockId());
        assertThat(matches.get(0).region().getMinY()).isEqualTo(600);
        assertThat(matches.get(1).blockId()).isEqualTo(bottom.blockId());
    }

    @Test
    void anchorsOnThePrintedCaptionRatherThanOnEmissionOrder() {
        // The model emitted "Abbildung 2" first, but on the page it is the LOWER
        // figure. Anchoring on the caption beats zipping in reading order.
        FigurePlacement abb2 = block(1, "Abbildung 2", 0);
        FigurePlacement abb1 = block(1, "Abbildung 1", 1);

        List<FigureRegion> glyphs = new ArrayList<>();
        glyphs.addAll(line("Abbildung 1", 100, 585));  // caption under the upper figure
        glyphs.addAll(line("Abbildung 2", 100, 285));  // caption under the lower figure

        List<FigureMatcher.Match> matches = matcher.match(List.of(abb2, abb1),
            Map.of(1, page(List.of(rect(100, 600, 200, 150), rect(100, 300, 200, 150)), glyphs)));

        assertThat(matches).hasSize(2);
        assertThat(matches).anySatisfy(m -> {
            assertThat(m.blockId()).isEqualTo(abb1.blockId());
            assertThat(m.region().getMinY()).isEqualTo(600);
        });
        assertThat(matches).anySatisfy(m -> {
            assertThat(m.blockId()).isEqualTo(abb2.blockId());
            assertThat(m.region().getMinY()).isEqualTo(300);
        });
    }

    @Test
    void picksTheLargerRegionWhenADecorativeOneSurvivedTheFilters() {
        FigurePlacement only = block(3, null, 0);

        List<FigureMatcher.Match> matches = matcher.match(List.of(only),
            Map.of(3, page(List.of(rect(100, 600, 240, 200), rect(400, 700, 45, 45)), List.of())));

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).region().getWidth()).isEqualTo(240);
    }

    @Test
    void skipsAPageWhereTheExtraRegionsAreTooCloseInSizeToRank() {
        // Two near-identical candidates for one block: ranking by area is noise.
        FigurePlacement only = block(1, null, 0);

        List<FigureMatcher.Match> matches = matcher.match(List.of(only),
            Map.of(1, page(List.of(rect(100, 600, 200, 150), rect(100, 300, 205, 152)), List.of())));

        assertThat(matches).isEmpty();
    }

    @Test
    void leavesSurplusBlocksEmptyRatherThanGuessing() {
        FigurePlacement a = block(1, null, 0);
        FigurePlacement b = block(1, null, 1);
        FigurePlacement c = block(1, null, 2);

        List<FigureMatcher.Match> matches = matcher.match(List.of(a, b, c),
            Map.of(1, page(List.of(rect(100, 600, 200, 150)), List.of())));

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).blockId()).isEqualTo(a.blockId());
    }

    @Test
    void ignoresBlocksWithNoPageNumber() {
        List<FigureMatcher.Match> matches = matcher.match(List.of(block(null, "Figure 1", 0)),
            Map.of(1, page(List.of(rect(100, 600, 200, 150)), List.of())));

        assertThat(matches).isEmpty();
    }

    @Test
    void ignoresBlocksOnAPageWhereGeometryFoundNothing() {
        List<FigureMatcher.Match> matches = matcher.match(List.of(block(7, "Figure 1", 0)),
            Map.of(1, page(List.of(rect(100, 600, 200, 150)), List.of())));

        assertThat(matches).isEmpty();
    }

    @Test
    void neverGivesTheSameRegionToTwoBlocks() {
        FigurePlacement a = block(1, "Figure 1", 0);
        FigurePlacement b = block(1, "Figure 1", 1); // duplicate label, one region

        List<FigureRegion> glyphs = line("Figure 1", 100, 585);
        List<FigureMatcher.Match> matches = matcher.match(List.of(a, b),
            Map.of(1, page(List.of(rect(100, 600, 200, 150)), glyphs)));

        assertThat(matches).hasSize(1);
        assertThat(matches).extracting(FigureMatcher.Match::region).doesNotHaveDuplicates();
    }

    @Test
    void foldsTheManySpellingsOfAFigureLabel() {
        assertThat(FigureMatcher.normalizeLabel("Abbildung 3")).isEqualTo("fig3");
        assertThat(FigureMatcher.normalizeLabel("Abb. 3")).isEqualTo("fig3");
        assertThat(FigureMatcher.normalizeLabel("Figure 3:")).isEqualTo("fig3");
        assertThat(FigureMatcher.normalizeLabel("Fig 3")).isEqualTo("fig3");
        assertThat(FigureMatcher.normalizeLabel("  ")).isNull();
        assertThat(FigureMatcher.normalizeLabel(null)).isNull();
    }

    @Test
    void reconstructsTextLinesFromIndividualGlyphs() {
        List<FigureRegion> glyphs = new ArrayList<>();
        glyphs.addAll(line("Abbildung 1", 100, 500));
        glyphs.addAll(line("Aufgabe 2", 100, 400));

        List<FigureMatcher.TextLine> lines = FigureMatcher.TextLine.reconstruct(glyphs);

        // Spaces are dropped: plenty of PDFs encode gaps as text positioning rather
        // than a space glyph, so lines are compared through normalizeLabel, which
        // strips whitespace anyway. Assert that property, not the raw spacing.
        assertThat(lines).hasSize(2);
        assertThat(FigureMatcher.normalizeLabel(lines.get(0).text())).isEqualTo("fig1");
        assertThat(lines.get(1).text()).startsWith("Aufgabe").doesNotContain("Abbildung");
        assertThat(lines.get(0).rect().getMinY()).isGreaterThan(lines.get(1).rect().getMinY());
    }

    @Test
    void doesNotAnchorOnACaptionFarBelowTheFigure() {
        // Label matches, but the text is 200pt down the page — that is body text
        // that happens to mention the figure, not the caption.
        FigurePlacement only = block(1, "Figure 1", 0);
        List<FigureRegion> glyphs = line("Figure 1", 100, 380);

        List<FigureMatcher.Match> matches = matcher.match(List.of(only),
            Map.of(1, page(List.of(rect(100, 600, 200, 150)), glyphs)));

        // Falls through to the zip, which still assigns the only region.
        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).region().getMinY()).isEqualTo(600);
    }
}
