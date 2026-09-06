package app.parse.figures;

import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.springframework.stereotype.Component;

import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Reduces everything a page drew to the handful of rectangles that could be a
 * figure.
 *
 * <p>Ordering matters more than any single threshold here. Body-text wrappers are
 * rejected first, because a form wrapping the whole page body would otherwise
 * swallow every real figure during containment dedup. Nested artwork is collapsed
 * next, so a form holding one image counts once rather than twice. Only then are
 * the leftovers merged — a generator that slices one image into strips emits rects
 * that would each die individually on the aspect-ratio guard, so merging has to
 * happen before filtering, not after.
 */
@Component
class FigureRegionDetector {

    /** Tiled image strips abut exactly; a couple of points absorbs rounding. */
    private static final double MERGE_GAP = 2.0;
    private static final double MIN_SIDE = 40;
    private static final double MIN_AREA = 3_000;
    private static final double MAX_PAGE_FRACTION = 0.85;
    private static final double MAX_ASPECT = 12;
    private static final double BAND_FRACTION = 0.06;
    private static final double BAND_REJECT = 0.60;
    private static final double CONTAINED = 0.95;
    private static final double WRAPPER_GLYPHS = 0.60;

    /**
     * Below this the "is it a body-text wrapper?" ratio is meaningless: on a page
     * that is almost entirely one figure, the figure legitimately contains most of
     * the page's few glyphs.
     */
    private static final int MIN_GLYPHS_FOR_WRAPPER_TEST = 50;

    /** Figure candidates on one page, in reading order. */
    List<Rectangle2D> detect(PDPage page, List<FigureRegion> regions) {
        List<Rectangle2D> glyphs = regions.stream()
            .filter(r -> r.kind() == FigureRegion.Kind.GLYPH)
            .map(FigureRegion::rect)
            .toList();

        List<Rectangle2D> artwork = new ArrayList<>(regions.stream()
            .filter(FigureRegion::isArtwork)
            .filter(r -> !isBodyTextWrapper(r.rect(), glyphs))
            .map(r -> (Rectangle2D) r.rect().clone())
            .toList());

        List<Rectangle2D> outermost = dropContained(artwork);
        List<Rectangle2D> merged = mergeAdjacent(outermost);

        List<Rectangle2D> kept = new ArrayList<>();
        for (Rectangle2D r : merged) {
            if (isPlausibleFigure(r, page)) kept.add(r);
        }
        kept.sort(readingOrder());
        return kept;
    }

    /** Top-to-bottom, then left-to-right. PDF y grows upward, so max-y descends. */
    static Comparator<Rectangle2D> readingOrder() {
        return Comparator.<Rectangle2D>comparingDouble(r -> -r.getMaxY())
            .thenComparingDouble(Rectangle2D::getMinX);
    }

    private static boolean isBodyTextWrapper(Rectangle2D rect, List<Rectangle2D> glyphs) {
        if (glyphs.size() < MIN_GLYPHS_FOR_WRAPPER_TEST) return false;
        long inside = glyphs.stream().filter(g -> rect.contains(g.getCenterX(), g.getCenterY())).count();
        return (double) inside / glyphs.size() > WRAPPER_GLYPHS;
    }

    /** Keeps the outermost of any nested pair, so a form and its image count once. */
    private static List<Rectangle2D> dropContained(List<Rectangle2D> rects) {
        List<Rectangle2D> out = new ArrayList<>();
        for (int i = 0; i < rects.size(); i++) {
            Rectangle2D a = rects.get(i);
            boolean swallowed = false;
            for (int j = 0; j < rects.size() && !swallowed; j++) {
                if (i == j) continue;
                Rectangle2D b = rects.get(j);
                if (area(a) > area(b)) continue;
                // Equal rects: keep the first, drop the later duplicate.
                if (area(a) == area(b) && j > i) continue;
                if (area(a) > 0 && area(intersect(a, b)) / area(a) >= CONTAINED) swallowed = true;
            }
            if (!swallowed) out.add(a);
        }
        return out;
    }

    private static List<Rectangle2D> mergeAdjacent(List<Rectangle2D> rects) {
        List<Rectangle2D> work = new ArrayList<>(rects);
        boolean merged = true;
        while (merged) {
            merged = false;
            outer:
            for (int i = 0; i < work.size(); i++) {
                for (int j = i + 1; j < work.size(); j++) {
                    if (near(work.get(i), work.get(j))) {
                        Rectangle2D union = work.get(i).createUnion(work.get(j));
                        work.remove(j);
                        work.remove(i);
                        work.add(union);
                        merged = true;
                        break outer;
                    }
                }
            }
        }
        return work;
    }

    private static boolean near(Rectangle2D a, Rectangle2D b) {
        Rectangle2D grown = new Rectangle2D.Double(
            a.getX() - MERGE_GAP, a.getY() - MERGE_GAP,
            a.getWidth() + 2 * MERGE_GAP, a.getHeight() + 2 * MERGE_GAP);
        return grown.intersects(b);
    }

    private static boolean isPlausibleFigure(Rectangle2D r, PDPage page) {
        if (r.getWidth() < MIN_SIDE || r.getHeight() < MIN_SIDE) return false;
        if (area(r) < MIN_AREA) return false;

        PDRectangle crop = page.getCropBox();
        double pageArea = crop.getWidth() * crop.getHeight();
        if (pageArea > 0 && area(r) > MAX_PAGE_FRACTION * pageArea) return false;

        double aspect = r.getWidth() / r.getHeight();
        if (aspect > MAX_ASPECT || aspect < 1 / MAX_ASPECT) return false;

        return !mostlyInPageFurniture(r, crop);
    }

    /** Logos and rules live in the top/bottom margins; real figures do not. */
    private static boolean mostlyInPageFurniture(Rectangle2D r, PDRectangle crop) {
        double band = crop.getHeight() * BAND_FRACTION;
        Rectangle2D top = new Rectangle2D.Double(
            crop.getLowerLeftX(), crop.getUpperRightY() - band, crop.getWidth(), band);
        Rectangle2D bottom = new Rectangle2D.Double(
            crop.getLowerLeftX(), crop.getLowerLeftY(), crop.getWidth(), band);
        double inBands = area(intersect(r, top)) + area(intersect(r, bottom));
        return area(r) > 0 && inBands / area(r) > BAND_REJECT;
    }

    /**
     * Drops artwork that appears at the same spot on three or more pages — a
     * letterhead or watermark, never a figure. Mutates the supplied per-page lists.
     */
    static void demoteRepeated(Map<Integer, List<Rectangle2D>> byPage) {
        if (byPage.size() < 3) return;
        List<Rectangle2D> all = byPage.values().stream().flatMap(Collection::stream).toList();
        List<Rectangle2D> repeated = new ArrayList<>();
        for (Rectangle2D candidate : all) {
            long pagesWithIt = byPage.values().stream()
                .filter(page -> page.stream().anyMatch(r -> sameSpot(r, candidate)))
                .count();
            if (pagesWithIt >= 3 && repeated.stream().noneMatch(r -> sameSpot(r, candidate))) {
                repeated.add(candidate);
            }
        }
        for (List<Rectangle2D> page : byPage.values()) {
            page.removeIf(r -> repeated.stream().anyMatch(rep -> sameSpot(r, rep)));
        }
    }

    private static boolean sameSpot(Rectangle2D a, Rectangle2D b) {
        return Math.abs(a.getX() - b.getX()) < 3 && Math.abs(a.getY() - b.getY()) < 3
            && Math.abs(a.getWidth() - b.getWidth()) < 3
            && Math.abs(a.getHeight() - b.getHeight()) < 3;
    }

    private static Rectangle2D intersect(Rectangle2D a, Rectangle2D b) {
        Rectangle2D out = a.createIntersection(b);
        return out.isEmpty() ? new Rectangle2D.Double() : out;
    }

    private static double area(Rectangle2D r) {
        return Math.max(0, r.getWidth()) * Math.max(0, r.getHeight());
    }
}
