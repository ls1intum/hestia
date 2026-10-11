package de.tum.cit.hestia.learninggoalhub.extraction;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The non-blank lines shown to the direct session extractor, together with their source offsets.
 */
final class NumberedLines {

    /**
     * The prompt permits one contiguous source passage of at most ten numbered lines.
     *
     * <p>It was five, which sat just below where the model naturally draws a passage: on a measured
     * 59-unit run, 48 replies cited too widely and the median overshoot was seven lines — 16 of them
     * missed by exactly one line. Each rejection cost a full re-ask of the whole session, and the
     * re-ask then complied 43 times out of 48, so the calls bought a boundary the model could hit
     * but would not choose. Ten covers 77% of that overshoot.
     *
     * <p>Ten lines of slide material is about one bullet list, which is a fair claim about where a
     * broad instructor-level outcome comes from. The genuinely sprawling citations — the 22-, 43- and
     * 54-line ranges in that run — are still rejected, and still degrade to an unsupported outcome
     * rather than being narrowed to a passage nobody verified.
     */
    private static final int MAX_LINE_DISTANCE = 9;

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
