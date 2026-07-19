package de.tum.cit.hestia.learninggoalhub.extraction;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
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

    /** A stitched-snippet fragment must be at least this long to count as a grounded match. */
    private static final int MIN_FRAGMENT_LENGTH = 20;

    /**
     * A needle reduced to letters/digits must keep at least this length for the aggressive matching
     * pass — below that (e.g. a snippet of bullet glyphs and operators) a match says nothing.
     */
    private static final int MIN_AGGRESSIVE_LENGTH = 20;

    private SourcePageResolver() {
    }

    public record Resolution(Integer page, boolean grounded) {
    }

    public static Resolution resolve(String rawText, int[] pageOffsets,
                                     int unitStart, int unitEnd, String snippet) {
        if (pageOffsets == null || pageOffsets.length < 2) {
            return new Resolution(null, false);
        }
        if (rawText == null || snippet == null || snippet.isBlank()) {
            return new Resolution(pageForOffset(pageOffsets, unitStart).orElse(null), false);
        }

        int start = Math.max(0, Math.min(unitStart, rawText.length()));
        int end = Math.max(start, Math.min(unitEnd, rawText.length()));
        OptionalInt offset = locate(rawText, start, end, snippet);
        if (offset.isPresent()) {
            return new Resolution(pageForOffset(pageOffsets, offset.getAsInt()).orElse(null), true);
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
                offset = locate(rawText, start, end, fragment);
                if (offset.isPresent()) {
                    return new Resolution(pageForOffset(pageOffsets, offset.getAsInt()).orElse(null), true);
                }
            }
        }

        return new Resolution(pageForOffset(pageOffsets, unitStart).orElse(null), false);
    }

    /**
     * Finds {@code needle} verbatim, preferring the unit's {@code [start,end)} range, then the whole
     * text, then whitespace-normalized, then reduced to letters/digits; returns the match's offset in
     * the original text.
     */
    private static OptionalInt locate(String rawText, int start, int end, String needle) {
        int match = rawText.indexOf(needle, start);
        while (match >= 0) {
            if (match + needle.length() <= end) {
                return OptionalInt.of(match);
            }
            match = rawText.indexOf(needle, match + 1);
        }

        match = rawText.indexOf(needle);
        if (match >= 0) {
            return OptionalInt.of(match);
        }

        String normalizedNeedle = normalize(needle).text();
        NormalizedText normalizedUnit = normalize(rawText.substring(start, end), start);
        match = normalizedUnit.text().indexOf(normalizedNeedle);
        if (match >= 0) {
            return OptionalInt.of(normalizedUnit.originalOffsets()[match]);
        }

        NormalizedText normalizedText = normalize(rawText);
        match = normalizedText.text().indexOf(normalizedNeedle);
        if (match >= 0) {
            return OptionalInt.of(normalizedText.originalOffsets()[match]);
        }

        // Last resort: models quoting slide content tend to silently drop layout glyphs the text
        // extraction kept (bullets like "□", operators like "⋅"). Reduce both sides to letters and
        // digits so those omissions cannot break the match.
        String aggressiveNeedle = normalizeAggressive(needle, 0).text().trim();
        if (aggressiveNeedle.length() >= MIN_AGGRESSIVE_LENGTH) {
            NormalizedText aggressiveUnit = normalizeAggressive(rawText.substring(start, end), start);
            match = aggressiveUnit.text().indexOf(aggressiveNeedle);
            if (match >= 0) {
                return OptionalInt.of(aggressiveUnit.originalOffsets()[match]);
            }

            NormalizedText aggressiveText = normalizeAggressive(rawText, 0);
            match = aggressiveText.text().indexOf(aggressiveNeedle);
            if (match >= 0) {
                return OptionalInt.of(aggressiveText.originalOffsets()[match]);
            }
        }

        return OptionalInt.empty();
    }

    private static Optional<Integer> pageForOffset(int[] pageOffsets, int offset) {
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
            if (!keep.test(text.charAt(i))) {
                int firstSeparator = i;
                while (i + 1 < text.length() && !keep.test(text.charAt(i + 1))) {
                    i++;
                }
                normalized.append(' ');
                offsets[offsetCount++] = originalOffset + firstSeparator;
            } else {
                normalized.append(text.charAt(i));
                offsets[offsetCount++] = originalOffset + i;
            }
        }
        return new NormalizedText(normalized.toString(), java.util.Arrays.copyOf(offsets, offsetCount));
    }

    private record NormalizedText(String text, int[] originalOffsets) {
    }
}
