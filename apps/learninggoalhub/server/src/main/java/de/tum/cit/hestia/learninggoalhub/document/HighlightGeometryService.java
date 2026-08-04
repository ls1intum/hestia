package de.tum.cit.hestia.learninggoalhub.document;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;
import org.springframework.stereotype.Service;

/** Maps a page-local PDFTextStripper character range to crop-relative PDF rectangles. */
@Service
public class HighlightGeometryService {

    private static final double PADDING = 1.5;

    /**
     * Extracts one page with the same PDFTextStripper defaults used for persisted raw text and maps
     * the requested half-open character range to one padded rectangle per visual line. Coordinates
     * use the unrotated PDF user space relative to the page CropBox; the client applies page rotation.
     */
    public List<HighlightRect> findHighlightRects(PDDocument document, int pageNumber,
                                                   int start, int end) throws IOException {
        if (document == null || pageNumber < 1 || pageNumber > document.getNumberOfPages()) {
            return List.of();
        }

        PositionTrackingStripper stripper = new PositionTrackingStripper(pageNumber);
        CapturedText captured = stripper.capture(document);
        int rangeStart = Math.max(0, Math.min(start, captured.text().length()));
        int rangeEnd = Math.max(rangeStart, Math.min(end, captured.text().length()));
        if (rangeStart == rangeEnd) {
            return List.of();
        }

        PDRectangle cropBox = document.getPage(pageNumber - 1).getCropBox();
        Set<TextPosition> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Bounds> lines = new ArrayList<>();
        for (int i = rangeStart; i < rangeEnd; i++) {
            TextPosition position = captured.positions().get(i);
            if (position == null || !seen.add(position)) {
                continue;
            }
            Bounds glyph = bounds(position, cropBox);
            int lineIndex = lineIndex(lines, glyph);
            if (lineIndex < 0) {
                lines.add(glyph);
            } else {
                lines.set(lineIndex, lines.get(lineIndex).union(glyph));
            }
        }

        return lines.stream()
                .map(line -> line.pad(PADDING, cropBox))
                .map(line -> new HighlightRect(line.x(), line.y(), line.width(), line.height()))
                .toList();
    }

    private static int lineIndex(List<Bounds> lines, Bounds candidate) {
        for (int i = 0; i < lines.size(); i++) {
            Bounds line = lines.get(i);
            double gap = Math.max(line.y(), candidate.y())
                    - Math.min(line.y() + line.height(), candidate.y() + candidate.height());
            if (gap <= Math.max(2.0, Math.min(line.height(), candidate.height()) * 0.5)) {
                return i;
            }
        }
        return -1;
    }

    private static Bounds bounds(TextPosition position, PDRectangle cropBox) {
        Matrix matrix = position.getTextMatrix();
        double startX = matrix.getTranslateX();
        double startY = matrix.getTranslateY();
        double endX = position.getEndX();
        double endY = position.getEndY();
        double baselineX = endX - startX;
        double baselineY = endY - startY;
        double baselineLength = Math.hypot(baselineX, baselineY);
        if (baselineLength < 0.001) {
            baselineX = matrix.getScaleX();
            baselineY = matrix.getShearY();
            baselineLength = Math.hypot(baselineX, baselineY);
        }
        if (baselineLength < 0.001) {
            baselineX = 1;
            baselineY = 0;
            baselineLength = 1;
        }

        double unitX = baselineX / baselineLength;
        double unitY = baselineY / baselineLength;
        double unitNormalX = -unitY;
        double unitNormalY = unitX;
        double height = Math.max(1.0, position.getHeight());
        double otherX = startX + unitNormalX * height;
        double otherY = startY + unitNormalY * height;
        double minX = Math.min(Math.min(startX, endX), otherX);
        double maxX = Math.max(Math.max(startX, endX), otherX);
        double minY = Math.min(Math.min(startY, endY), otherY);
        double maxY = Math.max(Math.max(startY, endY), otherY);

        // LegacyPDFStreamEngine translates non-zero CropBox origins before creating TextPositions;
        // retain the explicit arguments here to document and enforce the coordinate contract.
        double cropWidth = cropBox.getWidth();
        double cropHeight = cropBox.getHeight();
        return new Bounds(
                Math.max(0, Math.min(cropWidth, minX)),
                Math.max(0, Math.min(cropHeight, minY)),
                Math.max(0, Math.min(cropWidth, maxX) - Math.max(0, Math.min(cropWidth, minX))),
                Math.max(0, Math.min(cropHeight, maxY) - Math.max(0, Math.min(cropHeight, minY))));
    }

    private record Bounds(double x, double y, double width, double height) {

        private Bounds union(Bounds other) {
            double minX = Math.min(x, other.x);
            double minY = Math.min(y, other.y);
            double maxX = Math.max(x + width, other.x + other.width);
            double maxY = Math.max(y + height, other.y + other.height);
            return new Bounds(minX, minY, maxX - minX, maxY - minY);
        }

        private Bounds pad(double padding, PDRectangle cropBox) {
            double minX = Math.max(0, x - padding);
            double minY = Math.max(0, y - padding);
            double maxX = Math.min(cropBox.getWidth(), x + width + padding);
            double maxY = Math.min(cropBox.getHeight(), y + height + padding);
            return new Bounds(minX, minY, maxX - minX, maxY - minY);
        }
    }

    private record CapturedText(String text, List<TextPosition> positions) {
    }

    /** Captures every emitted character, including separator characters with a null position. */
    private static final class PositionTrackingStripper extends PDFTextStripper {

        private final StringBuilder text = new StringBuilder();
        private final List<TextPosition> positions = new ArrayList<>();
        private boolean inParagraph;

        private PositionTrackingStripper(int pageNumber) throws IOException {
            setStartPage(pageNumber);
            setEndPage(pageNumber);
        }

        private CapturedText capture(PDDocument document) throws IOException {
            writeText(document, new CaptureWriter());
            return new CapturedText(text.toString(), Collections.unmodifiableList(new ArrayList<>(positions)));
        }

        @Override
        protected void writeString(String value, List<TextPosition> textPositions) {
            int unicodeLength = textPositions.stream()
                    .map(TextPosition::getUnicode)
                    .filter(java.util.Objects::nonNull)
                    .mapToInt(String::length)
                    .sum();
            if (unicodeLength == value.length()) {
                int valueIndex = 0;
                for (TextPosition position : textPositions) {
                    int length = position.getUnicode() == null ? 0 : position.getUnicode().length();
                    for (int i = 0; i < length && valueIndex < value.length(); i++) {
                        append(value.charAt(valueIndex++), position);
                    }
                }
                return;
            }
            if (textPositions.size() == value.length()) {
                for (int i = 0; i < value.length(); i++) {
                    append(value.charAt(i), textPositions.get(i));
                }
                return;
            }
            for (int i = 0; i < value.length(); i++) {
                append(value.charAt(i), i < textPositions.size() ? textPositions.get(i) : null);
            }
        }

        @Override
        protected void writeString(String value) {
            appendUnpositioned(value);
        }

        @Override
        protected void writeCharacters(TextPosition position) {
            String value = position.getUnicode();
            if (value != null) {
                for (int i = 0; i < value.length(); i++) {
                    append(value.charAt(i), position);
                }
            }
        }

        @Override
        protected void writeLineSeparator() {
            appendUnpositioned(getLineSeparator());
        }

        @Override
        protected void writeWordSeparator() {
            appendUnpositioned(getWordSeparator());
        }

        @Override
        protected void writeParagraphSeparator() throws IOException {
            writeParagraphEnd();
            writeParagraphStart();
        }

        @Override
        protected void writeParagraphStart() throws IOException {
            if (inParagraph) {
                writeParagraphEnd();
            }
            appendUnpositioned(getParagraphStart());
            inParagraph = true;
        }

        @Override
        protected void writeParagraphEnd() throws IOException {
            if (!inParagraph) {
                writeParagraphStart();
            }
            appendUnpositioned(getParagraphEnd());
            inParagraph = false;
        }

        @Override
        protected void writePageStart() {
            appendUnpositioned(getPageStart());
        }

        @Override
        protected void writePageEnd() {
            appendUnpositioned(getPageEnd());
        }

        @Override
        protected void startArticle() throws IOException {
            startArticle(true);
        }

        @Override
        protected void startArticle(boolean isContent) {
            appendUnpositioned(getArticleStart());
        }

        @Override
        protected void endArticle() {
            appendUnpositioned(getArticleEnd());
        }

        private void append(char value, TextPosition position) {
            if (value != 0) {
                text.append(value);
                positions.add(position);
            }
        }

        private void appendUnpositioned(String value) {
            if (value == null) {
                return;
            }
            for (int i = 0; i < value.length(); i++) {
                append(value.charAt(i), null);
            }
        }

        private final class CaptureWriter extends Writer {

            @Override
            public void write(char[] cbuf, int off, int len) {
                appendUnpositioned(new String(cbuf, off, len));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        }
    }
}
