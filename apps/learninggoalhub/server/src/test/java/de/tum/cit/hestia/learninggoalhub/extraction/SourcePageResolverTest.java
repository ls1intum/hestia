package de.tum.cit.hestia.learninggoalhub.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SourcePageResolverTest {

    @Test
    void resolvesExactSnippetWithinUnitBeforeWholeDocument() {
        String rawText = "first page\nunit text\nsecond page\nunit text";

        SourcePageResolver.Resolution resolution = SourcePageResolver.resolve(
                rawText, new int[]{0, 22, rawText.length()}, 22, rawText.length(), "unit text");

        assertThat(resolution.page()).isEqualTo(2);
        assertThat(resolution.grounded()).isTrue();
    }

    @Test
    void resolvesSnippetWithDifferentWhitespace() {
        String rawText = "first page\nalpha\nbeta\nsecond page";

        SourcePageResolver.Resolution resolution = SourcePageResolver.resolve(
                rawText, new int[]{0, 20, rawText.length()}, 0, rawText.length(), "alpha\n\nbeta");

        assertThat(resolution.page()).isEqualTo(1);
        assertThat(resolution.grounded()).isTrue();
    }

    @Test
    void blankSnippetFallsBackToUnitStart() {
        SourcePageResolver.Resolution resolution = SourcePageResolver.resolve(
                "first second", new int[]{0, 6, 12}, 6, 12, "  \n");

        assertThat(resolution.page()).isEqualTo(2);
        assertThat(resolution.grounded()).isFalse();
    }

    @Test
    void nullPageOffsetsCannotResolvePage() {
        SourcePageResolver.Resolution resolution = SourcePageResolver.resolve("text", null, 0, 4, "text");

        assertThat(resolution.page()).isNull();
        assertThat(resolution.grounded()).isFalse();
    }

    @Test
    void mapsOffsetsAtPageBoundaries() {
        int[] pageOffsets = {0, 5, 10, 15};

        SourcePageResolver.Resolution first = SourcePageResolver.resolve(
                "012345678901234", pageOffsets, 0, 1, "0");
        SourcePageResolver.Resolution second = SourcePageResolver.resolve(
                "012345678901234", pageOffsets, 5, 6, "5");
        SourcePageResolver.Resolution third = SourcePageResolver.resolve(
                "012345678901234", pageOffsets, 14, 15, "4");
        SourcePageResolver.Resolution fallback = SourcePageResolver.resolve(
                "012345678901234", pageOffsets, 10, 15, "not found");

        assertThat(first.page()).isEqualTo(1);
        assertThat(first.grounded()).isTrue();
        assertThat(second.page()).isEqualTo(2);
        assertThat(second.grounded()).isTrue();
        assertThat(third.page()).isEqualTo(3);
        assertThat(third.grounded()).isTrue();
        assertThat(fallback.page()).isEqualTo(3);
        assertThat(fallback.grounded()).isFalse();
    }

    @Test
    void resolvesExactSnippetDocumentWideWhenOutsideUnit() {
        String rawText = "unit text\ntarget text";

        SourcePageResolver.Resolution resolution = SourcePageResolver.resolve(
                rawText, new int[]{0, 10, rawText.length()}, 0, 9, "target text");

        assertThat(resolution.page()).isEqualTo(2);
        assertThat(resolution.grounded()).isTrue();
    }

    @Test
    void resolvesNormalizedSnippetDocumentWideWhenOutsideUnit() {
        String rawText = "unit text\nalpha  beta";

        SourcePageResolver.Resolution resolution = SourcePageResolver.resolve(
                rawText, new int[]{0, 10, rawText.length()}, 0, 9, "alpha\nbeta");

        assertThat(resolution.page()).isEqualTo(2);
        assertThat(resolution.grounded()).isTrue();
    }
}
