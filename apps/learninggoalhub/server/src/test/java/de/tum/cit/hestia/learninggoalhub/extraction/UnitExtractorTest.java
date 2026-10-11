package de.tum.cit.hestia.learninggoalhub.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.hestia.learninggoalhub.document.Document;
import de.tum.cit.hestia.learninggoalhub.document.DocumentKind;
import de.tum.cit.hestia.learninggoalhub.document.DocumentContentRepository;
import de.tum.cit.hestia.learninggoalhub.document.HighlightGeometryService;
import de.tum.cit.hestia.learninggoalhub.document.PageDescriptionService.FigureDescription;
import de.tum.cit.hestia.learninggoalhub.extraction.UnitExtractor.ResolvedSource;
import de.tum.cit.hestia.learninggoalhub.extraction.UnitExtractor.SessionUnit;
import de.tum.cit.hestia.learninggoalhub.extraction.UnitExtractor.SourceLineSelection;
import de.tum.cit.hestia.learninggoalhub.extraction.UnitExtractor.Window;
import de.tum.cit.hestia.learninggoalhub.goal.EvidenceKind;
import de.tum.cit.hestia.learninggoalhub.goal.GoalSourceRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

class UnitExtractorTest {

    private static UnitExtractor extractor(int unitMaxChars) {
        return new UnitExtractor(mock(SessionExtractionService.class), mock(DocumentContentRepository.class),
                mock(HighlightGeometryService.class), mock(GoalSourceRepository.class), unitMaxChars, 3_000, 3_000);
    }

    private static Document document(String text, int[] pageOffsets) {
        Document document = mock(Document.class);
        when(document.getId()).thenReturn(7L);
        when(document.getRawText()).thenReturn(text);
        when(document.getPageOffsets()).thenReturn(pageOffsets);
        return document;
    }

    @Test
    void windowsCutAtPageBoundariesWithinTheBudget() {
        String text = "aaaaaabbbbbbcccccc";
        Document document = document(text, new int[]{0, 6, 12, 18});

        assertThat(extractor(10).windows("Lecture", document, text, 0, 18, 1, 3)).containsExactly(
                new Window(0, 6, 1, 1), new Window(6, 12, 2, 2), new Window(12, 18, 3, 3));
        assertThat(extractor(12).windows("Lecture", document, text, 0, 18, 1, 3)).containsExactly(
                new Window(0, 12, 1, 2), new Window(12, 18, 3, 3));
    }

    @Test
    void aSinglePageAboveTheBudgetIsKeptWhole() {
        String text = "x".repeat(30);
        Document document = document(text, new int[]{0, 30});

        assertThat(extractor(10).windows("Lecture", document, text, 0, 30, 1, 1))
                .containsExactly(new Window(0, 30, 1, 1));
    }

    @Test
    void withoutPageOffsetsWindowsCutAtLineBoundaries() {
        String text = "aaaa\nbbbb\ncccc\n";
        Document document = document(text, null);

        assertThat(extractor(10).windows("Notes", document, text, 0, text.length(), null, null))
                .containsExactly(new Window(0, 10, null, null), new Window(10, 15, null, null));
    }

    @Test
    void resolveAcceptsAValidLineSpan() {
        String text = "Random forests\nBagging reduces variance\n";
        Document document = document(text, new int[]{0, 15, text.length()});
        Window window = new Window(0, text.length(), 1, 2);

        ResolvedSource source = extractor(0).resolve(document, window, new SourceLineSelection(1, 1, null),
                List.of(), "");

        assertThat(source.evidenceKind()).isEqualTo(EvidenceKind.TEXT);
        assertThat(source.resolution().page()).isEqualTo(2);
        assertThat(UnitExtractor.snippetOf(document, source)).isEqualTo("Bagging reduces variance");
    }

    @Test
    void resolveFallsBackToTheFigureThenToUnsupported() {
        String text = "Random forests\n";
        Document document = document(text, new int[]{0, text.length()});
        Window window = new Window(0, text.length(), 1, 1);
        List<FigureDescription> figures = List.of(new FigureDescription(1, "A diagram of tree votes."));

        ResolvedSource figure = extractor(0).resolve(document, window, new SourceLineSelection(4, 9, 0),
                figures, "");
        assertThat(figure.evidenceKind()).isEqualTo(EvidenceKind.FIGURE);
        assertThat(figure.resolution().page()).isEqualTo(1);

        ResolvedSource unsupported = extractor(0).resolve(document, window, new SourceLineSelection(null, null, 3),
                figures, "");
        assertThat(unsupported.evidenceKind()).isEqualTo(EvidenceKind.UNSUPPORTED);
        assertThat(unsupported.resolution().page()).isNull();
    }

    /** The kind picks the prompt, and each kind scales its allowance by its own characters-per-skill. */
    @Test
    void extractPicksThePromptAndBudgetByTheDocumentKind() {
        SessionExtractionService service = mock(SessionExtractionService.class);
        UnitExtractor extractor = new UnitExtractor(service, mock(DocumentContentRepository.class),
                mock(HighlightGeometryService.class), mock(GoalSourceRepository.class), 0, 3_000, 1_000);
        String text = "x".repeat(3_000);

        extractor.extract(null, unit(DocumentKind.EXERCISE, text), "en", null);
        verify(service).extract(eq("Unit"), eq(text), eq("en"), eq("English"), isNull(), anyList(), eq(3),
                eq(DocumentKind.EXERCISE));

        extractor.extract(null, unit(DocumentKind.LECTURE, text), "en", null);
        verify(service).extract(eq("Unit"), eq(text), eq("en"), eq("English"), isNull(), anyList(), eq(1),
                eq(DocumentKind.LECTURE));

        extractor.extract(null, unit(null, text), "en", null);
        verify(service).extract(eq("Unit"), eq(text), eq("en"), eq("English"), isNull(), anyList(), eq(1),
                isNull());
    }

    private static SessionUnit<Void> unit(DocumentKind kind, String text) {
        Document document = document(text, null);
        when(document.getKind()).thenReturn(kind);
        return new SessionUnit<>(null, document, new Window(0, text.length(), null, null), "Unit", text, List.of());
    }
}
