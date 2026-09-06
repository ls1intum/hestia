package app.parse.figures;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Decides which detected rectangle belongs to which parsed figure block.
 *
 * <p>The rule this is built around: an empty figure block is a self-evident
 * "upload one here" affordance, while a wrong image is an invisible
 * data-quality bug. So every ambiguity resolves to leaving the block empty
 * rather than to a coin flip.
 */
@Component
class FigureMatcher {

    private static final Logger log = LoggerFactory.getLogger(FigureMatcher.class);

    /** A caption sits just under its figure; further than this and it is body text. */
    private static final double CAPTION_GAP = 40;
    private static final double CAPTION_X_OVERLAP = 0.5;

    /**
     * When the largest unmatched regions are this close in size, ranking them by
     * area is noise rather than signal — better to skip the page entirely.
     */
    private static final double AMBIGUITY_RATIO = 1.3;

    /** Everything one page drew, as the matcher needs it. */
    record PageContent(List<Rectangle2D> regions, List<FigureRegion> glyphs) {}

    record Match(UUID blockId, int pageNumber, Rectangle2D region) {}

    List<Match> match(List<FigurePlacement> placements, Map<Integer, PageContent> pages) {
        Map<Integer, List<FigurePlacement>> byPage = new LinkedHashMap<>();
        for (FigurePlacement p : placements) {
            if (p.pageNumber() == null) continue;
            byPage.computeIfAbsent(p.pageNumber(), k -> new ArrayList<>()).add(p);
        }

        List<Match> out = new ArrayList<>();
        for (Map.Entry<Integer, List<FigurePlacement>> e : byPage.entrySet()) {
            PageContent content = pages.get(e.getKey());
            if (content == null || content.regions().isEmpty()) continue;
            List<FigurePlacement> blocks = new ArrayList<>(e.getValue());
            blocks.sort(Comparator.comparingInt(FigurePlacement::order));
            out.addAll(matchPage(e.getKey(), blocks, content));
        }
        return out;
    }

    private List<Match> matchPage(int pageNumber, List<FigurePlacement> blocks, PageContent content) {
        List<Rectangle2D> free = new ArrayList<>(content.regions());
        List<FigurePlacement> unmatched = new ArrayList<>(blocks);
        List<Match> out = new ArrayList<>();

        // 1. Anchor on the printed caption. This trusts what the PDF actually says
        //    over the order the model happened to emit figures in.
        List<TextLine> lines = TextLine.reconstruct(content.glyphs());
        for (FigurePlacement block : new ArrayList<>(unmatched)) {
            Rectangle2D anchored = anchorByLabel(block, lines, free);
            if (anchored != null) {
                out.add(new Match(block.blockId(), pageNumber, anchored));
                free.remove(anchored);
                unmatched.remove(block);
            }
        }
        if (unmatched.isEmpty() || free.isEmpty()) return out;

        // 2. Zip what is left in reading order.
        List<Rectangle2D> candidates = free;
        if (free.size() > unmatched.size()) {
            candidates = largest(free, unmatched.size());
            if (candidates == null) {
                log.info("figure-extract page={} skipped: {} regions for {} blocks are too "
                    + "close in size to rank", pageNumber, free.size(), unmatched.size());
                return out;
            }
            candidates = new ArrayList<>(candidates);
            candidates.sort(FigureRegionDetector.readingOrder());
        }
        int n = Math.min(unmatched.size(), candidates.size());
        for (int i = 0; i < n; i++) {
            out.add(new Match(unmatched.get(i).blockId(), pageNumber, candidates.get(i)));
        }
        return out;
    }

    /** The region sitting directly above a line that reads like this block's label. */
    private static Rectangle2D anchorByLabel(
        FigurePlacement block, List<TextLine> lines, List<Rectangle2D> free) {
        String wanted = normalizeLabel(block.label());
        if (wanted == null) return null;
        for (TextLine line : lines) {
            if (!normalizeLabel(line.text()).startsWith(wanted)) continue;
            for (Rectangle2D r : free) {
                double gap = r.getMinY() - line.rect().getMaxY();
                if (gap < 0 || gap > CAPTION_GAP) continue;
                double overlap = Math.min(r.getMaxX(), line.rect().getMaxX())
                    - Math.max(r.getMinX(), line.rect().getMinX());
                if (overlap > 0 && overlap >= CAPTION_X_OVERLAP * Math.min(
                    r.getWidth(), line.rect().getWidth())) {
                    return r;
                }
            }
        }
        return null;
    }

    /**
     * Folds the many spellings of a figure label to one token, so "Abbildung 3",
     * "Abb. 3" and "Figure 3:" all compare equal.
     */
    static String normalizeLabel(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9äöüß]+", "")
            .replaceFirst("^(abbildungen|abbildung|abb|figures|figure|fig|bild|grafik)", "fig");
        return s.isBlank() ? null : s;
    }

    /** Top {@code n} by area, or null when the cut point is not a meaningful one. */
    private static List<Rectangle2D> largest(List<Rectangle2D> rects, int n) {
        List<Rectangle2D> sorted = new ArrayList<>(rects);
        sorted.sort(Comparator.comparingDouble((Rectangle2D r) -> area(r)).reversed());
        double keep = area(sorted.get(n - 1));
        double drop = area(sorted.get(n));
        if (drop > 0 && keep / drop < AMBIGUITY_RATIO) return null;
        return sorted.subList(0, n);
    }

    private static double area(Rectangle2D r) {
        return Math.max(0, r.getWidth()) * Math.max(0, r.getHeight());
    }

    /** One rendered line of text, rebuilt from individual glyph boxes. */
    record TextLine(String text, Rectangle2D rect) {

        static List<TextLine> reconstruct(List<FigureRegion> glyphs) {
            List<FigureRegion> sorted = new ArrayList<>(glyphs.stream()
                .filter(g -> g.text() != null && !g.text().isBlank())
                .toList());
            sorted.sort(Comparator
                .<FigureRegion>comparingDouble(g -> -g.rect().getMinY())
                .thenComparingDouble(g -> g.rect().getMinX()));

            List<TextLine> out = new ArrayList<>();
            List<FigureRegion> current = new ArrayList<>();
            for (FigureRegion g : sorted) {
                if (!current.isEmpty() && !onSameLine(current.get(0), g)) {
                    out.add(join(current));
                    current = new ArrayList<>();
                }
                current.add(g);
            }
            if (!current.isEmpty()) out.add(join(current));
            return out;
        }

        private static boolean onSameLine(FigureRegion a, FigureRegion b) {
            double tolerance = Math.max(a.rect().getHeight(), 2) * 0.6;
            return Math.abs(a.rect().getMinY() - b.rect().getMinY()) <= tolerance;
        }

        private static TextLine join(List<FigureRegion> glyphs) {
            StringBuilder sb = new StringBuilder();
            Rectangle2D box = null;
            for (FigureRegion g : glyphs) {
                sb.append(g.text());
                box = box == null ? (Rectangle2D) g.rect().clone() : box.createUnion(g.rect());
            }
            return new TextLine(sb.toString(), box);
        }
    }
}
