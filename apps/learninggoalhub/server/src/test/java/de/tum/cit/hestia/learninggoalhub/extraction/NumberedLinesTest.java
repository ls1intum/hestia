package de.tum.cit.hestia.learninggoalhub.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NumberedLinesTest {

    @Test
    void omitsBlankLinesButKeepsTheirOffsetsInSpans() {
        String text = "Heading\n\n  \nSupporting line\nFinal line";
        NumberedLines lines = NumberedLines.of(text);

        assertThat(lines.lineCount()).isEqualTo(3);
        assertThat(lines.render()).isEqualTo("[0] Heading\n[1] Supporting line\n[2] Final line");
        assertThat(lines.span(0, 1))
                .get()
                .satisfies(span -> assertThat(text.substring(span.start(), span.end()))
                        .isEqualTo("Heading\n\n  \nSupporting line"));
    }

    @Test
    void toleratesCrLfWhenRenderingAndSpanning() {
        String text = "First\r\n\r\nSecond";
        NumberedLines lines = NumberedLines.of(text);

        assertThat(lines.render()).isEqualTo("[0] First\n[1] Second");
        assertThat(text.substring(lines.span(0, 1).orElseThrow().start(),
                lines.span(0, 1).orElseThrow().end())).isEqualTo(text);
    }

    @Test
    void rejectsInvalidAndOverlongRanges() {
        NumberedLines lines = NumberedLines.of("one\ntwo\nthree");

        assertThat(lines.span(-1, 0)).isEmpty();
        assertThat(lines.span(0, 3)).isEmpty();
        assertThat(lines.span(2, 1)).isEmpty();
        assertThat(NumberedLines.of("line\n".repeat(12)).span(0, 11)).isEmpty();
    }

    @Test
    void acceptsFiveSourceLinesButRejectsSix() {
        NumberedLines lines = NumberedLines.of("line\n".repeat(6));

        assertThat(lines.span(0, 4)).isPresent();
        assertThat(lines.span(0, 5)).isEmpty();
        assertThat(lines.rejectionReason(0, 5)).contains("more than 5 numbered lines");
    }
}
