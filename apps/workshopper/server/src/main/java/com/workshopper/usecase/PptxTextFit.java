package com.workshopper.usecase;

import java.util.List;

/**
 * Estimates whether text fits a box and picks the largest font size that does.
 *
 * <p>Works from approximate character widths instead of real font metrics, so it runs in a
 * headless container without system fonts. The estimate is deliberately a little generous
 * with widths, so text tends to come out slightly smaller rather than overflowing.
 */
final class PptxTextFit {

    /** Line height as a multiple of the font size (single spacing plus a small margin). */
    private static final double LINE_HEIGHT = 1.2;

    private PptxTextFit() {}

    /**
     * Largest size between {@code minSize} and {@code maxSize} (in points, 0.5pt steps) at which
     * every paragraph, wrapped to {@code widthPt}, fits within {@code heightPt}. Returns
     * {@code minSize} when even that does not fit.
     */
    static double fitFontSize(List<String> paragraphs, double maxSize, double minSize, double widthPt, double heightPt) {
        if (paragraphs == null || paragraphs.isEmpty() || widthPt <= 0 || heightPt <= 0) return maxSize;
        for (double size = maxSize; size > minSize; size -= 0.5) {
            if (fits(paragraphs, size, widthPt, heightPt)) return size;
        }
        return minSize;
    }

    static boolean fits(List<String> paragraphs, double size, double widthPt, double heightPt) {
        int lines = 0;
        for (String p : paragraphs) {
            lines += lineCount(p == null ? "" : p, size, widthPt);
        }
        return lines * size * LINE_HEIGHT <= heightPt;
    }

    /** Number of lines {@code text} wraps to at {@code size} in a box {@code widthPt} wide. */
    static int lineCount(String text, double size, double widthPt) {
        int lines = 0;
        for (String paragraph : text.split("\n", -1)) {
            lines += Math.max(1, wrap(paragraph, size, widthPt));
        }
        return lines;
    }

    private static int wrap(String paragraph, double size, double widthPt) {
        if (paragraph.isBlank()) return 1;
        int lines = 1;
        double lineWidth = 0;
        double space = charWidth(' ') * size;
        for (String word : paragraph.trim().split("\\s+")) {
            double w = textWidth(word) * size;
            if (lineWidth == 0) {
                lineWidth = w;
            } else if (lineWidth + space + w <= widthPt) {
                lineWidth += space + w;
            } else {
                lines++;
                lineWidth = w;
            }
            // A single word wider than the box breaks across lines
            while (lineWidth > widthPt) {
                lines++;
                lineWidth -= widthPt;
            }
        }
        return lines;
    }

    private static double textWidth(String s) {
        double w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            w += charWidth(cp);
            i += Character.charCount(cp);
        }
        return w;
    }

    /** Approximate advance width of a character as a fraction of the font size, for a sans-serif font. */
    private static double charWidth(int cp) {
        if (cp == ' ') return 0.28;
        if ("iljtfr.,;:!|'()[]".indexOf(cp) >= 0) return 0.32;
        if (cp == 'm' || cp == 'w' || cp == 'M' || cp == 'W') return 0.85;
        if (Character.isUpperCase(cp)) return 0.68;
        if (Character.isDigit(cp)) return 0.56;
        if (cp < 0x2E80) return 0.55;  // other Latin, Greek, Cyrillic, punctuation
        return 1.0;                    // CJK, emoji and other full-width characters
    }
}
