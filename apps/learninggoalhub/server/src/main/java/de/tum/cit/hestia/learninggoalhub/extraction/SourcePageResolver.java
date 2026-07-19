package de.tum.cit.hestia.learninggoalhub.extraction;

import java.util.Optional;

/** Locates an extracted source snippet and maps its character offset to a PDF page. */
public final class SourcePageResolver {

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
        int match = rawText.indexOf(snippet, start);
        while (match >= 0) {
            if (match + snippet.length() <= end) {
                return new Resolution(pageForOffset(pageOffsets, match).orElse(null), true);
            }
            match = rawText.indexOf(snippet, match + 1);
        }

        match = rawText.indexOf(snippet);
        if (match >= 0) {
            return new Resolution(pageForOffset(pageOffsets, match).orElse(null), true);
        }

        String normalizedSnippet = normalize(snippet).text();
        NormalizedText normalizedUnit = normalize(rawText.substring(start, end), start);
        match = normalizedUnit.text().indexOf(normalizedSnippet);
        if (match >= 0) {
            return new Resolution(pageForOffset(pageOffsets, normalizedUnit.originalOffsets()[match])
                    .orElse(null), true);
        }

        NormalizedText normalizedText = normalize(rawText);
        match = normalizedText.text().indexOf(normalizedSnippet);
        if (match >= 0) {
            return new Resolution(pageForOffset(pageOffsets, normalizedText.originalOffsets()[match])
                    .orElse(null), true);
        }

        return new Resolution(pageForOffset(pageOffsets, unitStart).orElse(null), false);
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

    private static NormalizedText normalize(String text, int originalOffset) {
        StringBuilder normalized = new StringBuilder(text.length());
        int[] offsets = new int[text.length()];
        int offsetCount = 0;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                int firstWhitespace = i;
                while (i + 1 < text.length() && Character.isWhitespace(text.charAt(i + 1))) {
                    i++;
                }
                normalized.append(' ');
                offsets[offsetCount++] = originalOffset + firstWhitespace;
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
