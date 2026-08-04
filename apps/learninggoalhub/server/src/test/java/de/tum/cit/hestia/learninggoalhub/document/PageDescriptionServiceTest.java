package de.tum.cit.hestia.learninggoalhub.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.core.ParameterizedTypeReference;

class PageDescriptionServiceTest {

    @Test
    void appliesFixedEligibilityRules() {
        assertThat(PageDescriptionService.eligible("short")).isTrue();
        assertThat(PageDescriptionService.eligible("a".repeat(200))).isFalse();
        assertThat(PageDescriptionService.eligible("a".repeat(100) + " �".repeat(200))).isTrue();
        assertThat(PageDescriptionService.eligible(" \n\t ")).isTrue();
    }

    @Test
    void skipsBlankRawTextWithoutRequestingDescriptions() throws Exception {
        PageDescriptionRepository repository = mock(PageDescriptionRepository.class);
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        PageDescriptionService service = service(chatClient, repository);
        Document document = document(1L, "   ", new int[]{0, 3});

        service.describeEligiblePages(document, pdf(1));

        verify(repository, never()).findByDocumentId(any());
        verify(chatClient, never()).prompt();
    }

    @Test
    void doesNotRequestAlreadyDescribedPages() throws Exception {
        PageDescriptionRepository repository = mock(PageDescriptionRepository.class);
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        PageDescriptionService service = service(chatClient, repository);
        Document document = document(1L, "a", new int[]{0, 1});
        PageDescription existing = new PageDescription(document, 1, "already", "m");
        when(repository.findByDocumentId(1L)).thenReturn(List.of(existing));

        service.describeEligiblePages(document, pdf(1));

        verify(chatClient, never()).prompt();
    }

    @Test
    void failedFirstBatchDoesNotPreventSecondBatch() throws Exception {
        PageDescriptionRepository repository = mock(PageDescriptionRepository.class);
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(repository.findByDocumentId(1L)).thenReturn(List.of());
        when(chatClient.prompt().options(any(ChatOptions.class))
                .user(ArgumentMatchers.<Consumer<ChatClient.PromptUserSpec>>any())
                .call().entity(any(ParameterizedTypeReference.class)))
                .thenThrow(new IllegalStateException("first batch"))
                .thenReturn(List.of(new PageDescriptionService.PageReply(9, "diagram explanation")));
        PageDescriptionService service = service(chatClient, repository);
        Document document = document(1L, "x".repeat(9), offsets(9));

        service.describeEligiblePages(document, pdf(9));

        verify(repository).save(any(PageDescription.class));
    }

    @Test
    void dropsUnrequestedAndBlankReplies() throws Exception {
        PageDescriptionRepository repository = mock(PageDescriptionRepository.class);
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(repository.findByDocumentId(1L)).thenReturn(List.of());
        when(chatClient.prompt().options(any(ChatOptions.class))
                .user(ArgumentMatchers.<Consumer<ChatClient.PromptUserSpec>>any())
                .call().entity(any(ParameterizedTypeReference.class)))
                .thenReturn(List.of(
                        new PageDescriptionService.PageReply(1, " "),
                        new PageDescriptionService.PageReply(3, "not requested")));
        PageDescriptionService service = service(chatClient, repository);
        Document document = document(1L, "xx", new int[]{0, 1, 2});

        service.describeEligiblePages(document, pdf(2));

        verify(repository, never()).save(any(PageDescription.class));
    }

    private static PageDescriptionService service(ChatClient chatClient, PageDescriptionRepository repository) {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        return new PageDescriptionService(builder, repository, "vision-test-model");
    }

    private static Document document(Long id, String rawText, int[] offsets) {
        Document document = mock(Document.class);
        when(document.getId()).thenReturn(id);
        when(document.getFilename()).thenReturn("slides.pdf");
        when(document.getContentType()).thenReturn("application/pdf");
        when(document.getRawText()).thenReturn(rawText);
        when(document.getPageOffsets()).thenReturn(offsets);
        return document;
    }

    private static int[] offsets(int pages) {
        int[] offsets = new int[pages + 1];
        for (int i = 0; i <= pages; i++) {
            offsets[i] = i;
        }
        return offsets;
    }

    private static byte[] pdf(int pages) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                document.addPage(new PDPage());
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            document.save(bytes);
            return bytes.toByteArray();
        }
    }
}
