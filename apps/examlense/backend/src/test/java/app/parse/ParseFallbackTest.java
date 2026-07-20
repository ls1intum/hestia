package app.parse;

import app.ai.AiExceptions;
import app.ai.AiProvider;
import app.ai.AiProviderFactory;
import app.ai.ParserStrategies;
import app.ai.ParserStrategy;
import app.exam.ExamRepository;
import app.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the transient-failure fallback: when the default parser (Gemini) fails
 * transiently the pipeline retries once with {@link ParserStrategies#FALLBACK_ID}
 * (GPT-5.5); a non-transient failure does not fall back.
 */
class ParseFallbackTest {

    private static final UUID EXAM_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private ExamRepository examRepository;
    private StorageService storage;
    private AiProviderFactory providerFactory;
    private PdfTextExtractor textExtractor;
    private ParseInputBuilder inputBuilder;
    private ParsedExamPersister persister;
    private ParseMetricsRecorder metricsRecorder;
    private ParseProgress progress;
    private ParseExamService service;

    private AiProvider geminiProvider;
    private AiProvider gptProvider;

    @BeforeEach
    void setup() {
        examRepository = mock(ExamRepository.class);
        storage = mock(StorageService.class);
        providerFactory = mock(AiProviderFactory.class);
        textExtractor = mock(PdfTextExtractor.class);
        inputBuilder = mock(ParseInputBuilder.class);
        persister = mock(ParsedExamPersister.class);
        metricsRecorder = mock(ParseMetricsRecorder.class);
        progress = mock(ParseProgress.class);
        geminiProvider = mock(AiProvider.class);
        gptProvider = mock(AiProvider.class);

        service = new ParseExamService(
            examRepository, storage, providerFactory, textExtractor,
            inputBuilder, persister, metricsRecorder, progress
        );

        when(storage.download(eq("exam-pdfs"), anyString())).thenReturn(new byte[]{1, 2, 3});
        when(textExtractor.pageCount(any())).thenReturn(1);
        when(inputBuilder.build(any(), any(), any(), any()))
            .thenReturn(new AiProvider.TextContent("pdf-stub"));
        when(persister.persist(any(), any(), any(), any())).thenReturn(true);

        // Route each ParserStrategy to its provider mock.
        when(providerFactory.forParser(any())).thenAnswer(inv -> {
            ParserStrategy s = inv.getArgument(0);
            return ParserStrategies.FALLBACK_ID.equals(s.id()) ? gptProvider : geminiProvider;
        });
    }

    private void runParse() {
        service.runAsync(
            EXAM_ID.toString(), UUID.randomUUID().toString(), "exam-pdfs/file.pdf", null,
            ParserStrategies.resolve(ParserStrategies.DEFAULT_ID), false, System.nanoTime()
        );
    }

    @Test
    void transientPrimaryFailureFallsBackToGpt() {
        when(geminiProvider.chat(any()))
            .thenThrow(new AiExceptions.ProviderException("gemini busy", 503));
        when(gptProvider.chat(any())).thenReturn(new AiProvider.ChatResponse(
            Map.of("tasks", List.of(Map.of("type", "text", "prompt", "Q1"))),
            "gpt-5.5", "openai", null
        ));

        runParse();

        // The fallback model actually served: it was called and no failure was recorded.
        verify(gptProvider).chat(any());
        verify(progress, never()).fail(any(), anyString());
        verify(persister).persist(any(), any(), any(), any());
        // The exam is restamped with the serving model and metrics reflect it.
        verify(progress).setParserModel(EXAM_ID, "gpt-5.5");

        ArgumentCaptor<ParseAttempt> captor = ArgumentCaptor.forClass(ParseAttempt.class);
        verify(metricsRecorder).record(captor.capture());
        ParseAttempt attempt = captor.getValue();
        assertThat(attempt.parserModel).isEqualTo("gpt-5.5");
        assertThat(attempt.success).isTrue();
    }

    @Test
    void nonTransientPrimaryFailureDoesNotFallBack() {
        when(geminiProvider.chat(any()))
            .thenThrow(new AiExceptions.MalformedModelOutputException("no tool call"));

        runParse();

        // No fallback attempt; the exam fails with the structured-output message.
        verify(gptProvider, never()).chat(any());
        verify(providerFactory, never()).forParser(argThatIsGpt());
        verify(progress).fail(eq(EXAM_ID), eq(ParseErrorMessages.NOT_STRUCTURED));
        verify(progress, never()).setParserModel(any(), anyString());
        verify(persister, never()).persist(any(), any(), any(), any());

        ArgumentCaptor<ParseAttempt> captor = ArgumentCaptor.forClass(ParseAttempt.class);
        verify(metricsRecorder).record(captor.capture());
        assertThat(captor.getValue().parserModel).isEqualTo(ParserStrategies.DEFAULT_ID);
    }

    private static ParserStrategy argThatIsGpt() {
        return org.mockito.ArgumentMatchers.argThat(
            s -> s != null && ParserStrategies.FALLBACK_ID.equals(s.id()));
    }
}
