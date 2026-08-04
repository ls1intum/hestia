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
        assertThat(resolution.quality()).isEqualTo(SourceMatchQuality.EXACT_IN_SESSION);
        assertThat(resolution.matchStart()).isEqualTo(rawText.lastIndexOf("unit text"));
        assertThat(resolution.matchEnd()).isEqualTo(rawText.length());
    }

    @Test
    void resolvesSnippetWithDifferentWhitespace() {
        String rawText = "first page\nalpha\nbeta\nsecond page";

        SourcePageResolver.Resolution resolution = SourcePageResolver.resolve(
                rawText, new int[]{0, 20, rawText.length()}, 0, rawText.length(), "alpha\n\nbeta");

        assertThat(resolution.page()).isEqualTo(1);
        assertThat(resolution.quality()).isEqualTo(SourceMatchQuality.NORMALIZED);
        assertThat(resolution.matchStart()).isEqualTo(rawText.indexOf("alpha"));
        assertThat(resolution.matchEnd()).isEqualTo(rawText.indexOf("second page") - 1);
    }

    @Test
    void blankSnippetFallsBackToUnitStart() {
        SourcePageResolver.Resolution resolution = SourcePageResolver.resolve(
                "first second", new int[]{0, 6, 12}, 6, 12, "  \n");

        assertThat(resolution.page()).isEqualTo(2);
        assertThat(resolution.quality()).isEqualTo(SourceMatchQuality.NONE);
        assertThat(resolution.matchStart()).isNull();
        assertThat(resolution.matchEnd()).isNull();
    }

    /** A document without page boundaries still has verifiable text — only the page is unknown. */
    @Test
    void nullPageOffsetsStillVerifyTheQuote() {
        SourcePageResolver.Resolution resolution = SourcePageResolver.resolve("text", null, 0, 4, "text");

        assertThat(resolution.page()).isNull();
        assertThat(resolution.quality()).isEqualTo(SourceMatchQuality.EXACT_IN_SESSION);
        assertThat(resolution.matchStart()).isZero();
        assertThat(resolution.matchEnd()).isEqualTo(4);

        SourcePageResolver.Resolution absent = SourcePageResolver.resolve("text", null, 0, 4, "elsewhere");

        assertThat(absent.page()).isNull();
        assertThat(absent.quality()).isEqualTo(SourceMatchQuality.NONE);
        assertThat(absent.matchStart()).isNull();
        assertThat(absent.matchEnd()).isNull();
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
        assertThat(first.quality()).isEqualTo(SourceMatchQuality.EXACT_IN_SESSION);
        assertThat(second.page()).isEqualTo(2);
        assertThat(second.quality()).isEqualTo(SourceMatchQuality.EXACT_IN_SESSION);
        assertThat(third.page()).isEqualTo(3);
        assertThat(third.quality()).isEqualTo(SourceMatchQuality.EXACT_IN_SESSION);
        assertThat(fallback.page()).isEqualTo(3);
        assertThat(fallback.quality()).isEqualTo(SourceMatchQuality.NONE);
    }

    @Test
    void resolvesExactSnippetInDifferentSessionAsDocumentWide() {
        String rawText = "unit text\ntarget text";

        SourcePageResolver.Resolution resolution = SourcePageResolver.resolve(
                rawText, new int[]{0, 10, rawText.length()}, 0, 9, "target text");

        assertThat(resolution.page()).isEqualTo(2);
        assertThat(resolution.quality()).isEqualTo(SourceMatchQuality.EXACT_IN_DOCUMENT);
    }

    @Test
    void resolvesNormalizedSnippetDocumentWideWhenOutsideUnit() {
        String rawText = "unit text\nalpha  beta";

        SourcePageResolver.Resolution resolution = SourcePageResolver.resolve(
                rawText, new int[]{0, 10, rawText.length()}, 0, 9, "alpha\nbeta");

        assertThat(resolution.page()).isEqualTo(2);
        assertThat(resolution.quality()).isEqualTo(SourceMatchQuality.NORMALIZED);
        assertThat(resolution.matchStart()).isEqualTo(rawText.indexOf("alpha"));
        assertThat(resolution.matchEnd()).isEqualTo(rawText.length());
    }

    @Test
    void normalizedRangeCoversOriginalWhitespaceSpan() {
        String rawText = "alpha   beta";

        SourcePageResolver.Resolution resolution = SourcePageResolver.resolve(
                rawText, new int[]{0, rawText.length()}, 0, rawText.length(), "alpha beta");

        assertThat(resolution.quality()).isEqualTo(SourceMatchQuality.NORMALIZED);
        assertThat(resolution.matchStart()).isEqualTo(0);
        assertThat(resolution.matchEnd()).isEqualTo(rawText.length());
        assertThat(rawText.substring(resolution.matchStart(), resolution.matchEnd()))
                .isEqualTo("alpha   beta");
    }

    @Test
    void groundsStitchedSnippetOnItsLongestMatchingFragment() {
        String rawText = "first page filler text\nData Hazards entstehen durch Datenabhängigkeiten im Code";

        SourcePageResolver.Resolution resolution = SourcePageResolver.resolve(
                rawText, new int[]{0, 23, rawText.length()}, 0, rawText.length(),
                "Read After Write (RAW) ... Data Hazards entstehen durch Datenabhängigkeiten");

        assertThat(resolution.page()).isEqualTo(2);
        assertThat(resolution.quality()).isEqualTo(SourceMatchQuality.FRAGMENT);
        assertThat(resolution.matchStart()).isEqualTo(rawText.indexOf("Data Hazards"));
        assertThat(resolution.matchEnd()).isEqualTo(rawText.indexOf("Data Hazards")
                + "Data Hazards entstehen durch Datenabhängigkeiten".length());
    }

    @Test
    void groundsStitchedSnippetWithUnicodeEllipsis() {
        String rawText = "first page filler text\nEinfügen von NOPs verhindert den Konflikt im Fließband";

        SourcePageResolver.Resolution resolution = SourcePageResolver.resolve(
                rawText, new int[]{0, 23, rawText.length()}, 0, rawText.length(),
                "Einfügen von NOPs verhindert den Konflikt … Forwarding als Alternative");

        assertThat(resolution.page()).isEqualTo(2);
        assertThat(resolution.quality()).isEqualTo(SourceMatchQuality.FRAGMENT);
    }

    /**
     * Observed on a real corpus: the model reproduced "NP-vollständig" with a non-breaking hyphen.
     * Too short for the letters-and-digits pass, it used to count as unlocatable although the
     * document contains it.
     */
    @Test
    void groundsQuotesThatOnlySwapDashOrSpaceVariants() {
        String rawText = "first page filler text\nDas Problem ist NP-vollständig und teuer";

        SourcePageResolver.Resolution resolution = SourcePageResolver.resolve(
                rawText, new int[]{0, 23, rawText.length()}, 0, rawText.length(),
                "NP‑vollständig");

        assertThat(resolution.page()).isEqualTo(2);
        assertThat(resolution.quality()).isEqualTo(SourceMatchQuality.NORMALIZED);
    }

    @Test
    void doesNotGroundOnShortStitchedFragments() {
        String rawText = "first page filler text\nStalling und Forwarding";

        SourcePageResolver.Resolution resolution = SourcePageResolver.resolve(
                rawText, new int[]{0, 23, rawText.length()}, 0, rawText.length(),
                "Stalling ... Forwarding");

        assertThat(resolution.page()).isEqualTo(1);
        assertThat(resolution.quality()).isEqualTo(SourceMatchQuality.NONE);
    }

    @Test
    void groundsSnippetWithTrailingEllipsisOnItsPrefix() {
        String rawText = "first page filler text\nBranch Prediction verringert die Kosten von Kontrollkonflikten";

        SourcePageResolver.Resolution resolution = SourcePageResolver.resolve(
                rawText, new int[]{0, 23, rawText.length()}, 0, rawText.length(),
                "Branch Prediction verringert die Kosten...");

        assertThat(resolution.page()).isEqualTo(2);
        assertThat(resolution.quality()).isEqualTo(SourceMatchQuality.FRAGMENT);
    }

    @Test
    void groundsSnippetWhenModelDropsBulletGlyphs() {
        String rawText = "first page filler text\nZweistufige Adressierung\n□ Zunächst Aktivierung einer Zeile der Matrix\n□ Dann Selektion eines Elementes";

        SourcePageResolver.Resolution resolution = SourcePageResolver.resolve(
                rawText, new int[]{0, 23, rawText.length()}, 0, rawText.length(),
                "Zweistufige Adressierung Zunächst Aktivierung einer Zeile der Matrix Dann Selektion");

        assertThat(resolution.page()).isEqualTo(2);
        assertThat(resolution.quality()).isEqualTo(SourceMatchQuality.NORMALIZED);
    }

    @Test
    void groundsSnippetWhenModelDropsMathOperators() {
        String rawText = "first page filler text\nKosten eines CRn: C(CRn) = n ⋅ C(FA) = 5n = O(n) → linear";

        SourcePageResolver.Resolution resolution = SourcePageResolver.resolve(
                rawText, new int[]{0, 23, rawText.length()}, 0, rawText.length(),
                "Kosten eines CRn: C(CRn) = n  C(FA) = 5n = O(n) linear");

        assertThat(resolution.page()).isEqualTo(2);
        assertThat(resolution.quality()).isEqualTo(SourceMatchQuality.NORMALIZED);
    }

    @Test
    void doesNotGroundOnGlyphOnlySnippet() {
        SourcePageResolver.Resolution resolution = SourcePageResolver.resolve(
                "first second", new int[]{0, 6, 12}, 6, 12, "→ □ ■ ⋅ = () -- !!");

        assertThat(resolution.page()).isEqualTo(2);
        assertThat(resolution.quality()).isEqualTo(SourceMatchQuality.NONE);
    }

    @Test
    void groundsMultiLineSnippetInterruptedByExtractionGarbage() {
        String rawText = "first page filler text\nData Hazards entstehen durch Datenabhängigkeiten\n"
                + "□  bhängigkei  ≠   n  ik  \nControl Hazards entstehen durch Kontrollfluss";

        SourcePageResolver.Resolution resolution = SourcePageResolver.resolve(
                rawText, new int[]{0, 23, rawText.length()}, 0, rawText.length(),
                "Data Hazards entstehen durch Datenabhängigkeiten\nControl Hazards entstehen durch Kontrollfluss");

        assertThat(resolution.page()).isEqualTo(2);
        assertThat(resolution.quality()).isEqualTo(SourceMatchQuality.FRAGMENT);
    }

    @Test
    void unmatchedStitchedSnippetStillFallsBackUngrounded() {
        String rawText = "first page filler text\nsecond page other content entirely";

        SourcePageResolver.Resolution resolution = SourcePageResolver.resolve(
                rawText, new int[]{0, 23, rawText.length()}, 23, rawText.length(),
                "a fragment that appears nowhere ... another missing fragment entirely");

        assertThat(resolution.page()).isEqualTo(2);
        assertThat(resolution.quality()).isEqualTo(SourceMatchQuality.NONE);
    }
}
