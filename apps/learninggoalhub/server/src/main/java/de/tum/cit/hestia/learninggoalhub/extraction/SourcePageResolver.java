package de.tum.cit.hestia.learninggoalhub.extraction;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.IntPredicate;
import java.util.regex.Pattern;

/** Locates an extracted source snippet and maps its character offset to a PDF page. */
public final class SourcePageResolver {

    /**
     * Splits a snippet into independently locatable fragments: at "..." (three or more dots) or a
     * real ellipsis character (the model stitched passages together), and at line breaks (one quoted
     * line may be separated from the next by text-extraction garbage the model never saw).
     */
    private static final Pattern FRAGMENT_SEPARATOR = Pattern.compile("\\s*(?:\\.{3,}|…)\\s*|\\h*\\R+\\h*");

    /**
     * An ellipsis is the model admitting it skipped text, so such a snippet is never a full quote.
     * The aggressive matching pass reduces the needle to letters and digits and would drop the
     * ellipsis along with it, reporting a truncated quote as {@code NORMALIZED}; matches on a snippet
     * containing one are capped at {@code FRAGMENT} unless an exact tier matched it whole.
     */
    private static final Pattern ELLIPSIS = Pattern.compile("\\.{3,}|…");

    /** A stitched-snippet fragment must be at least this long to count as a grounded match. */
    private static final int MIN_FRAGMENT_LENGTH = 20;

    /**
     * A needle reduced to letters/digits must keep at least this length for the aggressive matching
     * pass — below that (e.g. a snippet of bullet glyphs and operators) a match says nothing.
     */
    private static final int MIN_AGGRESSIVE_LENGTH = 20;

    private SourcePageResolver() {
    }

    public record Resolution(Integer page, SourceMatchQuality quality, Integer matchStart, Integer matchEnd) {

        public boolean grounded() {
            return quality != SourceMatchQuality.NONE;
        }
    }

    public static Resolution resolve(String rawText, int[] pageOffsets,
                                     int unitStart, int unitEnd, String snippet) {
        if (rawText == null || snippet == null || snippet.isBlank()) {
            return new Resolution(pageForOffset(pageOffsets, unitStart).orElse(null), SourceMatchQuality.NONE,
                    null, null);
        }

        int start = Math.max(0, Math.min(unitStart, rawText.length()));
        int end = Math.max(start, Math.min(unitEnd, rawText.length()));
        Match match = locate(rawText, start, end, snippet);
        if (match != null) {
            return new Resolution(pageForOffset(pageOffsets, match.offset()).orElse(null),
                    cap(match.quality(), snippet), match.start(), match.end());
        }

        // The prompt demands one contiguous quote, but the model may still stitch fragments together
        // with an ellipsis, and even a faithful multi-line quote can be broken by extraction garbage
        // between its lines. Ground on the longest fragment substantial enough to be unambiguous.
        List<String> fragments = FRAGMENT_SEPARATOR.splitAsStream(snippet)
                .map(String::trim)
                .filter(f -> f.length() >= MIN_FRAGMENT_LENGTH)
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
        if (fragments.size() > 1 || (fragments.size() == 1 && !fragments.get(0).equals(snippet.trim()))) {
            for (String fragment : fragments) {
                match = locate(rawText, start, end, fragment);
                if (match != null) {
                    return new Resolution(pageForOffset(pageOffsets, match.offset()).orElse(null),
                            SourceMatchQuality.FRAGMENT, match.start(), match.end());
                }
            }
        }

        return new Resolution(pageForOffset(pageOffsets, unitStart).orElse(null), SourceMatchQuality.NONE,
                null, null);
    }

    /**
     * Finds {@code needle} verbatim, preferring the unit's {@code [start,end)} range, then the whole
     * text, then whitespace-normalized, then reduced to letters/digits; returns the match's offset in
     * the original text.
     */
    private static Match locate(String rawText, int start, int end, String needle) {
        int match = rawText.indexOf(needle, start);
        while (match >= 0) {
            if (match + needle.length() <= end) {
                return new Match(match, match + needle.length(), SourceMatchQuality.EXACT_IN_SESSION);
            }
            match = rawText.indexOf(needle, match + 1);
        }

        match = rawText.indexOf(needle);
        if (match >= 0) {
            return new Match(match, match + needle.length(), SourceMatchQuality.EXACT_IN_DOCUMENT);
        }

        String normalizedNeedle = normalize(needle).text();
        NormalizedText normalizedUnit = normalize(rawText.substring(start, end), start);
        match = normalizedUnit.text().indexOf(normalizedNeedle);
        if (match >= 0) {
            return normalizedMatch(normalizedUnit, match, normalizedNeedle.length());
        }

        NormalizedText normalizedText = normalize(rawText);
        match = normalizedText.text().indexOf(normalizedNeedle);
        if (match >= 0) {
            return normalizedMatch(normalizedText, match, normalizedNeedle.length());
        }

        // Last resort: models quoting slide content tend to silently drop layout glyphs the text
        // extraction kept (bullets like "□", operators like "⋅"). Reduce both sides to letters and
        // digits so those omissions cannot break the match.
        String aggressiveNeedle = normalizeAggressive(needle, 0).text().trim();
        if (aggressiveNeedle.length() >= MIN_AGGRESSIVE_LENGTH) {
            NormalizedText aggressiveUnit = normalizeAggressive(rawText.substring(start, end), start);
            match = aggressiveUnit.text().indexOf(aggressiveNeedle);
            if (match >= 0) {
                return normalizedMatch(aggressiveUnit, match, aggressiveNeedle.length());
            }

            NormalizedText aggressiveText = normalizeAggressive(rawText, 0);
            match = aggressiveText.text().indexOf(aggressiveNeedle);
            if (match >= 0) {
                return normalizedMatch(aggressiveText, match, aggressiveNeedle.length());
            }
        }

        return null;
    }

    private static Match normalizedMatch(NormalizedText normalized, int matchStart, int needleLength) {
        int originalStart = normalized.originalOffsets()[matchStart];
        int lastMatchedIndex = matchStart + needleLength - 1;
        int originalEnd = normalized.originalOffsets()[lastMatchedIndex] + 1;
        return new Match(originalStart, originalEnd, SourceMatchQuality.NORMALIZED);
    }

    /** Documents parsed without page boundaries (everything but PDFs) simply have no page to report. */
    static Optional<Integer> pageForOffset(int[] pageOffsets, int offset) {
        if (pageOffsets == null || pageOffsets.length < 2) {
            return Optional.empty();
        }
        if (offset < pageOffsets[0] || offset >= pageOffsets[pageOffsets.length - 1]) {
            return Optional.empty();
        }
        int low = 1;
        int high = pageOffsets.length - 1;
        int page = -1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (offset < pageOffsets[middle]) {
                page = middle;
                high = middle - 1;
            } else {
                low = middle + 1;
            }
        }
        return page < 1 ? Optional.empty() : Optional.of(page);
    }

    private static NormalizedText normalize(String text) {
        return normalize(text, 0);
    }

    /** Collapses whitespace runs to a single space, tracking each kept character's original offset. */
    private static NormalizedText normalize(String text, int originalOffset) {
        return normalize(text, originalOffset, c -> !Character.isWhitespace(c));
    }

    /**
     * Keeps only letters and digits, collapsing every other run (whitespace, bullets, operators)
     * to a single space.
     */
    private static NormalizedText normalizeAggressive(String text, int originalOffset) {
        return normalize(text, originalOffset, Character::isLetterOrDigit);
    }

    private static NormalizedText normalize(String text, int originalOffset, IntPredicate keep) {
        StringBuilder normalized = new StringBuilder(text.length());
        int[] offsets = new int[text.length()];
        int offsetCount = 0;
        for (int i = 0; i < text.length(); i++) {
            char folded = fold(text.charAt(i));
            if (!keep.test(folded)) {
                int firstSeparator = i;
                while (i + 1 < text.length() && !keep.test(fold(text.charAt(i + 1)))) {
                    i++;
                }
                normalized.append(' ');
                offsets[offsetCount++] = originalOffset + firstSeparator;
            } else {
                normalized.append(folded);
                offsets[offsetCount++] = originalOffset + i;
            }
        }
        return new NormalizedText(normalized.toString(), java.util.Arrays.copyOf(offsets, offsetCount));
    }

    /**
     * Maps the dash and space variants a model tends to substitute onto their plain counterparts, so
     * that "NP‑vollständig" with a non-breaking hyphen still matches the document's "NP-vollständig".
     * Character-for-character identity is judged before any normalization, so this only ever affects
     * how a {@code NORMALIZED} match is reached, never an exact one.
     */
    private static char fold(char c) {
        return switch (c) {
            case '\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2015', '\u2212' -> '-';
            case '\u00A0', '\u2007', '\u2009', '\u202F' -> ' ';
            default -> c;
        };
    }

    private record NormalizedText(String text, int[] originalOffsets) {
    }

    private record Match(int start, int end, SourceMatchQuality quality) {

        private int offset() {
            return start;
        }
    }

    /**
     * Downgrades a normalized match on an ellipsis-bearing snippet to {@code FRAGMENT}. An exact tier
     * found the snippet including its ellipsis in the text and is left alone.
     */
    private static SourceMatchQuality cap(SourceMatchQuality quality, String snippet) {
        if (quality == SourceMatchQuality.NORMALIZED && ELLIPSIS.matcher(snippet).find()) {
            return SourceMatchQuality.FRAGMENT;
        }
        return quality;
    }
}
