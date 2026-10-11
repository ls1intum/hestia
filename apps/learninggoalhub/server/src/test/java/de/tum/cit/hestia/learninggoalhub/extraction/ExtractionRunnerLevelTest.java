package de.tum.cit.hestia.learninggoalhub.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.hestia.learninggoalhub.document.Document;
import de.tum.cit.hestia.learninggoalhub.document.DocumentKind;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyLevel;
import org.junit.jupiter.api.Test;

/** The hierarchy level follows the kind chosen at upload; the title only decides for kind-less documents. */
class ExtractionRunnerLevelTest {

    @Test
    void theKindDecidesTheLevelWhateverTheTitleSays() {
        assertThat(ExtractionRunner.levelFor(document(DocumentKind.EXERCISE), "Chapter 3: Sorting"))
                .isEqualTo(HierarchyLevel.EXERCISE);
        assertThat(ExtractionRunner.levelFor(document(DocumentKind.LECTURE), "Exercise 3: Sorting"))
                .isEqualTo(HierarchyLevel.SESSION);
    }

    @Test
    void aDocumentWithoutAKindKeepsTheTitleHeuristic() {
        Document legacy = document(null);

        assertThat(ExtractionRunner.levelFor(legacy, "Übungsblatt 3")).isEqualTo(HierarchyLevel.EXERCISE);
        assertThat(ExtractionRunner.levelFor(legacy, "Tutorial 2")).isEqualTo(HierarchyLevel.EXERCISE);
        assertThat(ExtractionRunner.levelFor(legacy, "Chapter 3: Sorting")).isEqualTo(HierarchyLevel.SESSION);
        assertThat(ExtractionRunner.levelFor(legacy, null)).isEqualTo(HierarchyLevel.SESSION);
    }

    private static Document document(DocumentKind kind) {
        Document document = mock(Document.class);
        when(document.getKind()).thenReturn(kind);
        return document;
    }
}
