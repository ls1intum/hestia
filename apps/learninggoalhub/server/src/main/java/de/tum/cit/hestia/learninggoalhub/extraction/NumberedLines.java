package de.tum.cit.hestia.learninggoalhub.extraction;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The non-blank lines shown to the direct session extractor, together with their source offsets.
 */
final class NumberedLines {

    /** The prompt permits one contiguous source passage of at most five numbered lines. */
    private static final int MAX_LINE_DISTANCE = 4;

    private final List<Line> lines;

    private NumberedLines(String sourceText) {
        this.lines = collectLines(sourceText == null ? "" : sourceText);
    }

    static NumberedLines of(String sourceText) {
        return new NumberedLines(sourceText);
    }

    String render() {
        return lines.stream()
                .map(line -> "[" + line.index() + "] " + line.content())
                .collect(Collectors.joining("\n"));
    }

    int lineCount() {
        return lines.size();
    }

    Optional<Span> span(int firstLine, int lastLine) {
        if (firstLine < 0 || firstLine > lastLine || lastLine >= lines.size()
                || lastLine - firstLine > MAX_LINE_DISTANCE) {
            return Optional.empty();
        }
        return Optional.of(new Span(lines.get(firstLine).start(), lines.get(lastLine).end()));
    }

    /**
     * Whether this range names real lines but too many of them.
     *
     * <p>Separates the one rejection that still tells us where an outcome came from — an ascending,
     * in-bounds range that is simply wider than {@value #MAX_LINE_DISTANCE} + 1 lines — from ranges
     * that point nowhere at all (descending, or past the end of the text).
     */
    boolean isInBoundsButTooWide(int firstLine, int lastLine) {
        return firstLine >= 0 && firstLine <= lastLine && lastLine < lines.size()
                && lastLine - firstLine > MAX_LINE_DISTANCE;
    }

    /** Why {@link #span} rejected this range — mirrors its checks, for the extraction-run log. */
    String rejectionReason(int firstLine, int lastLine) {
        if (firstLine < 0 || firstLine > lastLine) {
            return "not an ascending range";
        }
        if (lastLine >= lines.size()) {
            return "beyond the " + lines.size() + " numbered lines";
        }
        if (lastLine - firstLine > MAX_LINE_DISTANCE) {
            return "spans more than " + (MAX_LINE_DISTANCE + 1) + " numbered lines";
        }
        return "accepted";
    }

    private static List<Line> collectLines(String sourceText) {
        List<Line> lines = new ArrayList<>();
        int sourceStart = 0;
        int index = 0;
        while (sourceStart <= sourceText.length()) {
            int newline = sourceText.indexOf('\n', sourceStart);
            int rawEnd = newline < 0 ? sourceText.length() : newline;
            int contentEnd = rawEnd > sourceStart && sourceText.charAt(rawEnd - 1) == '\r'
                    ? rawEnd - 1 : rawEnd;
            String content = sourceText.substring(sourceStart, contentEnd);
            if (!content.isBlank()) {
                lines.add(new Line(index++, sourceStart, contentEnd, content));
            }
            if (newline < 0) {
                break;
            }
            sourceStart = newline + 1;
        }
        return List.copyOf(lines);
    }

    record Span(int start, int end) {
    }

    private record Line(int index, int start, int end, String content) {
    }
}
