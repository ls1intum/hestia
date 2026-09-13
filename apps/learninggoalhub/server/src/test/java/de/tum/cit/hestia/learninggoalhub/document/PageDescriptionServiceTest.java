package de.tum.cit.hestia.learninggoalhub.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.converter.StructuredOutputConverter;

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

        service.describeEligiblePages(document, pdf(1), "en", "English", Runnable::run);

        verify(repository, never()).findByDocumentId(any());
        verify(chatClient, never()).prompt();
    }

    @Test
    void doesNotRequestAlreadyDescribedPages() throws Exception {
        PageDescriptionRepository repository = mock(PageDescriptionRepository.class);
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        PageDescriptionService service = service(chatClient, repository);
        Document document = document(1L, "a", new int[]{0, 1});
        PageDescription existing = new PageDescription(document, 1, "already", "m", true,
                "en", PageDescriptionService.FIGURE_PROMPT_VERSION);
        when(repository.findByDocumentId(1L)).thenReturn(List.of(existing));

        service.describeEligiblePages(document, pdf(1), "en", "English", Runnable::run);

        verify(chatClient, never()).prompt();
    }

    @Test
    void failedFirstBatchDoesNotPreventSecondBatch() throws Exception {
        PageDescriptionRepository repository = mock(PageDescriptionRepository.class);
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(repository.findByDocumentId(1L)).thenReturn(List.of());
        when(chatClient.prompt().options(any(ChatOptions.class))
                .user(ArgumentMatchers.<Consumer<ChatClient.PromptUserSpec>>any())
                .call().entity(any(StructuredOutputConverter.class)))
                .thenThrow(new IllegalStateException("first batch"))
                .thenReturn(List.of(new PageDescriptionService.PageReply(9, "diagram explanation", true)));
        PageDescriptionService service = service(chatClient, repository);
        Document document = document(1L, "x".repeat(9), offsets(9));

        service.describeEligiblePages(document, pdf(9), "en", "English", Runnable::run);

        verify(repository).save(any(PageDescription.class));
    }

    /**
     * The bottleneck this removes: batches ran in a sequential loop, so a course of two large PDFs
     * never had more than two describe calls in flight however wide the pool. One measured run spent
     * 404 s here — 62% of the whole extraction — at an effective concurrency of 1.8 while never once
     * being rate-limited.
     */
    @Test
    void describesBatchesConcurrently() throws Exception {
        PageDescriptionRepository repository = mock(PageDescriptionRepository.class);
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(repository.findByDocumentId(1L)).thenReturn(List.of());

        // Three batches of eight pages. Each call blocks until all three have arrived, so the test
        // can only finish if they are genuinely in flight together.
        int batches = 3;
        CountDownLatch allArrived = new CountDownLatch(batches);
        AtomicInteger peakInFlight = new AtomicInteger();
        AtomicInteger inFlight = new AtomicInteger();
        when(chatClient.prompt().options(any(ChatOptions.class))
                .user(ArgumentMatchers.<Consumer<ChatClient.PromptUserSpec>>any())
                .call().entity(any(StructuredOutputConverter.class)))
                .thenAnswer(invocation -> {
                    peakInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                    allArrived.countDown();
                    if (!allArrived.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("batches were not concurrent");
                    }
                    inFlight.decrementAndGet();
                    return List.of();
                });

        int pages = PageDescriptionService.BATCH_SIZE * batches;
        Document document = document(1L, "x".repeat(pages), offsets(pages));
        ExecutorService executor = Executors.newFixedThreadPool(batches);
        try {
            service(chatClient, repository)
                    .describeEligiblePages(document, pdf(pages), "en", "English", executor);
        } finally {
            executor.shutdownNow();
        }

        assertThat(peakInFlight).hasValue(batches);
    }

    /** Persisting is ordered by batch, not by which batch answered first. */
    @Test
    void keepsPageOrderWhenBatchesFinishOutOfOrder() throws Exception {
        PageDescriptionRepository repository = mock(PageDescriptionRepository.class);
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(repository.findByDocumentId(1L)).thenReturn(List.of());

        int pages = PageDescriptionService.BATCH_SIZE * 2;
        // Every call answers for every page; each batch keeps only the pages it asked for, so the
        // replies carry no batch identity and the stored order can only come from the join order.
        List<PageDescriptionService.PageReply> everyPage = java.util.stream.IntStream.rangeClosed(1, pages)
                .mapToObj(page -> new PageDescriptionService.PageReply(page, "description " + page, true))
                .toList();
        AtomicInteger invocations = new AtomicInteger();
        when(chatClient.prompt().options(any(ChatOptions.class))
                .user(ArgumentMatchers.<Consumer<ChatClient.PromptUserSpec>>any())
                .call().entity(any(StructuredOutputConverter.class)))
                .thenAnswer(invocation -> {
                    if (invocations.getAndIncrement() == 0) {
                        Thread.sleep(60);  // the first batch dispatched is the last to answer
                    }
                    return everyPage;
                });

        Document document = document(1L, "x".repeat(pages), offsets(pages));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            service(chatClient, repository)
                    .describeEligiblePages(document, pdf(pages), "en", "English", executor);
        } finally {
            executor.shutdownNow();
        }

        ArgumentCaptor<PageDescription> saved = ArgumentCaptor.forClass(PageDescription.class);
        verify(repository, times(pages)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(PageDescription::getPage)
                .containsExactlyElementsOf(
                        java.util.stream.IntStream.rangeClosed(1, pages).boxed().toList());
    }

    @Test
    void dropsUnrequestedAndBlankReplies() throws Exception {
        PageDescriptionRepository repository = mock(PageDescriptionRepository.class);
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(repository.findByDocumentId(1L)).thenReturn(List.of());
        when(chatClient.prompt().options(any(ChatOptions.class))
                .user(ArgumentMatchers.<Consumer<ChatClient.PromptUserSpec>>any())
                .call().entity(any(StructuredOutputConverter.class)))
                .thenReturn(List.of(
                        new PageDescriptionService.PageReply(1, " ", true),
                        new PageDescriptionService.PageReply(3, "not requested", true)));
        PageDescriptionService service = service(chatClient, repository);
        Document document = document(1L, "xx", new int[]{0, 1, 2});

        service.describeEligiblePages(document, pdf(2), "en", "English", Runnable::run);

        verify(repository, never()).save(any(PageDescription.class));
    }

    @Test
    void storesTheModelsTeachesContentVerdictAndDefaultsItToTrue() throws Exception {
        PageDescriptionRepository repository = mock(PageDescriptionRepository.class);
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(repository.findByDocumentId(1L)).thenReturn(List.of());
        when(chatClient.prompt().options(any(ChatOptions.class))
                .user(ArgumentMatchers.<Consumer<ChatClient.PromptUserSpec>>any())
                .call().entity(any(StructuredOutputConverter.class)))
                .thenReturn(List.of(
                        new PageDescriptionService.PageReply(1, "A title slide.", false),
                        new PageDescriptionService.PageReply(2, "A state machine diagram.", true),
                        new PageDescriptionService.PageReply(3, "A terse reply.", null)));
        PageDescriptionService service = service(chatClient, repository);
        Document document = document(1L, "xxx", new int[]{0, 1, 2, 3});

        service.describeEligiblePages(document, pdf(3), "de", "German", Runnable::run);

        ArgumentCaptor<PageDescription> saved = ArgumentCaptor.forClass(PageDescription.class);
        verify(repository, times(3)).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(PageDescription::getPage, PageDescription::isTeachesContent)
                .containsExactly(tuple(1, false), tuple(2, true), tuple(3, true));
        assertThat(saved.getAllValues())
                .allSatisfy(page -> {
                    assertThat(page.getLanguage()).isEqualTo("de");
                    assertThat(page.getPromptVersion()).isEqualTo(PageDescriptionService.FIGURE_PROMPT_VERSION);
                });
    }

    @Test
    void refreshesStaleRowsInPlaceWhenLanguageOrPromptVersionDiffers() throws Exception {
        PageDescriptionRepository repository = mock(PageDescriptionRepository.class);
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        Document document = document(1L, "x", new int[]{0, 1});
        PageDescription existing = new PageDescription(document, 1, "English description", "old", true,
                "en", "figure-v1");
        when(repository.findByDocumentId(1L)).thenReturn(List.of(existing));
        when(chatClient.prompt().options(any(ChatOptions.class))
                .user(ArgumentMatchers.<Consumer<ChatClient.PromptUserSpec>>any())
                .call().entity(any(StructuredOutputConverter.class)))
                .thenReturn(List.of(new PageDescriptionService.PageReply(1, "Deutsche Beschreibung", true)));

        service(chatClient, repository).describeEligiblePages(document, pdf(1), "de", "German", Runnable::run);

        verify(repository).save(existing);
        assertThat(existing.getDescription()).isEqualTo("Deutsche Beschreibung");
        assertThat(existing.getLanguage()).isEqualTo("de");
        assertThat(existing.getPromptVersion()).isEqualTo(PageDescriptionService.FIGURE_PROMPT_VERSION);
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
