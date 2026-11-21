package de.tum.cit.hestia.assessment.parse;

import de.tum.cit.hestia.ai.AiExceptions;
import de.tum.cit.hestia.ai.LlmContent;
import de.tum.cit.hestia.ai.LlmGateway;
import de.tum.cit.hestia.ai.LlmJobs;
import de.tum.cit.hestia.ai.LlmRequest;
import de.tum.cit.hestia.ai.LlmResponse;
import de.tum.cit.hestia.ai.Provider;
import de.tum.cit.hestia.assessment.ai.ParserStrategies;
import de.tum.cit.hestia.assessment.ai.ParserStrategy;
import de.tum.cit.hestia.assessment.examination.ExaminationRepository;
import de.tum.cit.hestia.assessment.storage.StorageService;
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
 * Covers the parser fallback: when the default parser (Gemini) fails transiently or
 * its account is out of credits, the pipeline retries once with
 * {@link ParserStrategies#FALLBACK_ID} (GPT-5.5); other failures do not fall back.
 */
class ParseFallbackTest {

    private static final UUID EXAM_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private ExaminationRepository examRepository;
    private StorageService storage;
    private PdfPageCounter pageCounter;
    private ParseInputBuilder inputBuilder;
    private ParsedExaminationPersister persister;
    private ParseMetricsRecorder metricsRecorder;
    private ParseProgress progress;
    private ParseExaminationService service;

    /** One gateway; each parser model has its own stubbing, matched on the model id of the request. */
    private LlmGateway gateway;

    private static final String GEMINI_MODEL = ParserStrategies.resolve(ParserStrategies.DEFAULT_ID).providerModel();
    private static final String GPT_MODEL = ParserStrategies.resolve(ParserStrategies.FALLBACK_ID).providerModel();

    private static LlmRequest requestFor(String modelId) {
        return org.mockito.ArgumentMatchers.argThat(r -> r != null && modelId.equals(r.model().id()));
    }

    @BeforeEach
    void setup() {
        examRepository = mock(ExaminationRepository.class);
        storage = mock(StorageService.class);
        gateway = mock(LlmGateway.class);
        pageCounter = mock(PdfPageCounter.class);
        inputBuilder = mock(ParseInputBuilder.class);
        persister = mock(ParsedExaminationPersister.class);
        metricsRecorder = mock(ParseMetricsRecorder.class);
        progress = mock(ParseProgress.class);

        service = new ParseExaminationService(
            examRepository, storage, gateway, pageCounter,
            inputBuilder, persister, metricsRecorder, progress,
            mock(de.tum.cit.hestia.assessment.parse.figures.FigureExtractionService.class), mock(LlmJobs.class),
            mock(de.tum.cit.hestia.assessment.shared.Access.class)
        );

        when(storage.download(eq("exam-pdfs"), anyString())).thenReturn(new byte[]{1, 2, 3});
        when(pageCounter.pageCount(any())).thenReturn(1);
        when(inputBuilder.build(any(), any(), any()))
            .thenReturn(new LlmContent.TextContent("pdf-stub"));
        when(persister.persist(any(), any(), any()))
            .thenReturn(new ParsedExaminationPersister.PersistResult(true, java.util.List.of()));
    }

    private void runParse() {
        service.runAsync(
            EXAM_ID.toString(), UUID.randomUUID().toString(), "exam-pdfs/file.pdf",
            ParserStrategies.resolve(ParserStrategies.DEFAULT_ID), UUID.randomUUID(), System.nanoTime()
        );
    }

    @Test
    void transientPrimaryFailureFallsBackToGpt() {
        when(gateway.chat(requestFor(GEMINI_MODEL)))
            .thenThrow(new AiExceptions.ProviderException("gemini busy", 503));
        when(gateway.chat(requestFor(GPT_MODEL))).thenReturn(new LlmResponse(
            null, Map.of("tasks", List.of(Map.of("type", "text", "prompt", "Q1"))),
            "gpt-5.5", Provider.OPENAI, null
        ));

        runParse();

        // The fallback model actually served: it was called and no failure was recorded.
        verify(gateway).chat(requestFor(GPT_MODEL));
        verify(progress, never()).fail(any(), anyString());
        verify(persister).persist(any(), any(), any());
        // The exam is restamped with the serving model and metrics reflect it.
        verify(progress).setParserModel(EXAM_ID, "gpt-5.5");

        ArgumentCaptor<ParseAttempt> captor = ArgumentCaptor.forClass(ParseAttempt.class);
        verify(metricsRecorder).record(captor.capture());
        ParseAttempt attempt = captor.getValue();
        assertThat(attempt.parserModel).isEqualTo("gpt-5.5");
        assertThat(attempt.success).isTrue();
    }

    /** An exhausted Gemini account is not transient, but GPT-5.5 runs on another account. */
    @Test
    void primaryOutOfCreditsFallsBackToGpt() {
        when(gateway.chat(requestFor(GEMINI_MODEL)))
            .thenThrow(new AiExceptions.PaymentRequiredException("credits exhausted"));
        when(gateway.chat(requestFor(GPT_MODEL))).thenReturn(new LlmResponse(
            null, Map.of("tasks", List.of(Map.of("type", "text", "prompt", "Q1"))),
            "gpt-5.5", Provider.OPENAI, null
        ));

        runParse();

        verify(gateway).chat(requestFor(GPT_MODEL));
        verify(progress, never()).fail(any(), anyString());
        verify(persister).persist(any(), any(), any());
        verify(progress).setParserModel(EXAM_ID, "gpt-5.5");
    }

    @Test
    void nonTransientPrimaryFailureDoesNotFallBack() {
        when(gateway.chat(requestFor(GEMINI_MODEL)))
            .thenThrow(new AiExceptions.MalformedModelOutputException("no tool call"));

        runParse();

        // No fallback attempt; the exam fails with the structured-output message.
        verify(gateway, never()).chat(requestFor(GPT_MODEL));
        verify(progress).fail(eq(EXAM_ID), eq(ParseErrorMessages.NOT_STRUCTURED));
        verify(progress, never()).setParserModel(any(), anyString());
        verify(persister, never()).persist(any(), any(), any());

        ArgumentCaptor<ParseAttempt> captor = ArgumentCaptor.forClass(ParseAttempt.class);
        verify(metricsRecorder).record(captor.capture());
        assertThat(captor.getValue().parserModel).isEqualTo(ParserStrategies.DEFAULT_ID);
    }

    /**
     * A retired model fails with 410, which is deliberately outside the transient
     * set — falling back here would answer the exam with GPT-5.5 while the exam
     * still records the withdrawn model.
     */
    @Test
    void retiredPrimaryModelFailsWithoutFallingBack() {
        when(gateway.chat(requestFor(GEMINI_MODEL)))
            .thenThrow(new AiExceptions.ProviderException("retired", 410));

        runParse();

        verify(gateway, never()).chat(requestFor(GPT_MODEL));
        verify(progress).fail(eq(EXAM_ID), eq(ParseErrorMessages.AI_MODEL_RETIRED));
        verify(persister, never()).persist(any(), any(), any());
    }
}
