package app.parse;

import app.ai.AiProviderFactory;
import app.error.ApiException;
import app.exam.Exam;
import app.exam.ExamRepository;
import app.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Re-parsing an exam that was originally parsed by a now-withdrawn model. Only
 * this path can name one — new exams pick from the live catalog — and it must be
 * refused up front rather than rasterizing a PDF for a call that cannot be made.
 */
class ParseRetiredModelTest {

    private static final UUID EXAM_ID = UUID.fromString("00000000-0000-0000-0000-000000000009");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private final ExamRepository examRepository = mock(ExamRepository.class);
    private final ParseProgress progress = mock(ParseProgress.class);
    private final ParseInputBuilder inputBuilder = mock(ParseInputBuilder.class);

    private ParseExamService service() {
        return new ParseExamService(
            examRepository, mock(StorageService.class), mock(AiProviderFactory.class),
            mock(PdfPageCounter.class), inputBuilder, mock(ParsedExamPersister.class),
            mock(ParseMetricsRecorder.class), progress,
            mock(app.parse.figures.FigureExtractionService.class));
    }

    private void withExam() {
        Exam exam = new Exam();
        exam.setId(EXAM_ID);
        exam.setOwnerId(OWNER);
        when(examRepository.findById(EXAM_ID)).thenReturn(Optional.of(exam));
    }

    @Test
    void preflightRefusesARetiredParserModel() {
        withExam();

        assertThatThrownBy(() ->
            service().preflight(EXAM_ID.toString(), OWNER.toString(), "qwen3.6-35b-a3b"))
            .isInstanceOf(ApiException.class)
            .hasMessage(ParseErrorMessages.AI_MODEL_RETIRED)
            .extracting(e -> ((ApiException) e).status())
            .isEqualTo(HttpStatus.GONE);

        // Nothing was started: no status flip, no rasterize, no work queued.
        verify(examRepository, never()).save(any());
        verify(inputBuilder, never()).build(any(), any(), any());
        verify(progress, never()).notifyExam(any());
    }

    @Test
    void preflightStillAcceptsACurrentModel() {
        withExam();

        assertThat(service().preflight(EXAM_ID.toString(), OWNER.toString(), "gemini-3.5-flash").id())
            .isEqualTo("gemini-3.5-flash");
        verify(progress).notifyExam(EXAM_ID);
    }

    @Test
    void anUnknownIdStillFallsBackToTheDefaultRatherThanFailing() {
        withExam();

        // Unknown ≠ retired: a typo or a client from a newer build should not
        // block a parse, and the registry's default already covers it.
        assertThat(service().preflight(EXAM_ID.toString(), OWNER.toString(), "not-a-model").id())
            .isEqualTo("gemini-3.5-flash");
        verify(progress, never()).fail(any(), anyString());
    }
}
