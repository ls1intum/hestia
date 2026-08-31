package de.tum.cit.hestia.learninggoalhub.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.converter.StructuredOutputConverter;

class DocumentLectureBoundaryServiceTest {

    @Test
    void detectsBoundariesFromPagePreviewsForABookmarklessCombinedPdf() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt();
        when(spec.options(any(ChatOptions.class))).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.call().entity(any(StructuredOutputConverter.class))).thenReturn(
                new DocumentLectureBoundaryService.DetectedOutline(List.of(
                        new DocumentLectureBoundaryService.Boundary(1, "Lecture One"),
                        new DocumentLectureBoundaryService.Boundary(5, "Lecture Two"))));
        String text = "page\n".repeat(8);
        int[] offsets = {0, 5, 10, 15, 20, 25, 30, 35, 40};

        List<DocumentStructureService.SectionSpan> result =
                new DocumentLectureBoundaryService(builder, "test-model", 8)
                        .detect("combined.pdf", text, offsets);

        assertThat(result).extracting(DocumentStructureService.SectionSpan::title)
                .containsExactly("Lecture One", "Lecture Two");
        verify(spec).user(org.mockito.ArgumentMatchers.contains("[page 8] page"));
    }

    @Test
    void skipsBoundaryCallForASmallSingleLecturePdf() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);

        List<DocumentStructureService.SectionSpan> result =
                new DocumentLectureBoundaryService(builder, "test-model", 8)
                        .detect("lecture.pdf", "one page", new int[]{0, 8});

        assertThat(result).isEmpty();
        verify(chatClient, never()).prompt();
    }

    @Test
    void convertsDetectedPagesIntoGapFreeOrderedSections() {
        String text = "page one\npage two\npage three\npage four";
        int[] offsets = {0, 9, 18, 29, text.length()};
        DocumentLectureBoundaryService.DetectedOutline outline =
                new DocumentLectureBoundaryService.DetectedOutline(List.of(
                        new DocumentLectureBoundaryService.Boundary(1, "Lecture 1"),
                        new DocumentLectureBoundaryService.Boundary(3, "Lecture 2")));

        List<DocumentStructureService.SectionSpan> result =
                DocumentLectureBoundaryService.validate(outline, text.length(), offsets);

        assertThat(result).extracting(DocumentStructureService.SectionSpan::title)
                .containsExactly("Lecture 1", "Lecture 2");
        assertThat(result.getFirst().startOffset()).isZero();
        assertThat(result.getFirst().endOffset()).isEqualTo(result.get(1).startOffset());
        assertThat(result.getLast().endOffset()).isEqualTo(text.length());
        assertThat(result.getFirst().startPage()).isEqualTo(1);
        assertThat(result.getFirst().endPage()).isEqualTo(2);
        assertThat(result.getLast().startPage()).isEqualTo(3);
        assertThat(result.getLast().endPage()).isEqualTo(4);
    }

    @Test
    void keepsOneTeachingUnitUnsplit() {
        assertThat(DocumentLectureBoundaryService.validate(
                new DocumentLectureBoundaryService.DetectedOutline(List.of()), 10,
                new int[]{0, 10})).isEmpty();
        assertThat(DocumentLectureBoundaryService.validate(
                new DocumentLectureBoundaryService.DetectedOutline(List.of(
                        new DocumentLectureBoundaryService.Boundary(1, "One lecture"))), 10,
                new int[]{0, 10})).isEmpty();
    }

    @Test
    void rejectsMalformedBoundaryPlans() {
        assertThatThrownBy(() -> DocumentLectureBoundaryService.validate(
                new DocumentLectureBoundaryService.DetectedOutline(List.of(
                        new DocumentLectureBoundaryService.Boundary(2, "Lecture 1"),
                        new DocumentLectureBoundaryService.Boundary(3, "Lecture 2"))),
                30, new int[]{0, 10, 20, 30}))
                .hasMessageContaining("page 1");
        assertThatThrownBy(() -> DocumentLectureBoundaryService.validate(
                new DocumentLectureBoundaryService.DetectedOutline(List.of(
                        new DocumentLectureBoundaryService.Boundary(1, "Lecture"),
                        new DocumentLectureBoundaryService.Boundary(3, "Lecture"))),
                30, new int[]{0, 10, 20, 30}))
                .hasMessageContaining("distinct");
    }
}
