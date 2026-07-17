package de.tum.cit.hestia.learninggoalhub.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class SourcePageResolverTest {

    @Test
    void resolvesExactSnippetWithinUnitBeforeWholeDocument() {
        String rawText = "first page\nunit text\nsecond page\nunit text";

        Optional<Integer> page = SourcePageResolver.resolve(
                rawText, new int[]{0, 22, rawText.length()}, 22, rawText.length(), "unit text");

        assertThat(page).contains(2);
    }

    @Test
    void resolvesSnippetWithDifferentWhitespace() {
        String rawText = "first page\nalpha\nbeta\nsecond page";

        Optional<Integer> page = SourcePageResolver.resolve(
                rawText, new int[]{0, 20, rawText.length()}, 0, rawText.length(), "alpha\n\nbeta");

        assertThat(page).contains(1);
    }

    @Test
    void blankSnippetFallsBackToUnitStart() {
        Optional<Integer> page = SourcePageResolver.resolve(
                "first second", new int[]{0, 6, 12}, 6, 12, "  \n");

        assertThat(page).contains(2);
    }

    @Test
    void nullPageOffsetsCannotResolvePage() {
        assertThat(SourcePageResolver.resolve("text", null, 0, 4, "text")).isEmpty();
    }

    @Test
    void mapsOffsetsAtPageBoundaries() {
        int[] pageOffsets = {0, 5, 10, 15};

        assertThat(SourcePageResolver.resolve("012345678901234", pageOffsets, 0, 1, "0"))
                .contains(1);
        assertThat(SourcePageResolver.resolve("012345678901234", pageOffsets, 5, 6, "5"))
                .contains(2);
        assertThat(SourcePageResolver.resolve("012345678901234", pageOffsets, 14, 15, "4"))
                .contains(3);
        assertThat(SourcePageResolver.resolve("012345678901234", pageOffsets, 10, 15, "not found"))
                .contains(3);
    }
}
