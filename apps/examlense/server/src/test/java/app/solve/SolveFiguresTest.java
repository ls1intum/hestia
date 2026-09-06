package app.solve;

import app.ai.AiProvider;
import app.ai.SolverStrategies;
import app.grading.TaskGradeRepository;
import app.prompts.Prompts;
import app.section.Section;
import app.section.SectionBlock;
import app.section.SectionBlockRepository;
import app.section.SectionFigure;
import app.section.SectionFigureRepository;
import app.section.SectionRepository;
import app.sse.SseHub;
import app.storage.StorageService;
import app.task.TaskAnswerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * Until now the solver could not see figures at all: it received the literal
 * token "[Figure 2.1]" and nothing else, so an uploaded image was stored,
 * displayed, and then ignored by the model answering the question about it.
 *
 * <p>These tests pin the two halves of the fix — images do reach the request, and
 * they are withheld from a model that cannot accept them, since the solver model
 * is pinned at exam creation and old exams may be bound to a text-only one.
 */
class SolveFiguresTest {

    private static final UUID EXAM = UUID.randomUUID();
    private static final UUID SECTION = UUID.randomUUID();

    private SectionRepository sectionRepository;
    private SectionBlockRepository blockRepository;
    private SectionFigureRepository figureRepository;
    private StorageService storage;
    private SolveCore core;

    @BeforeEach
    void setUp() {
        sectionRepository = mock(SectionRepository.class);
        blockRepository = mock(SectionBlockRepository.class);
        figureRepository = mock(SectionFigureRepository.class);
        storage = mock(StorageService.class);
        core = new SolveCore(
            sectionRepository, blockRepository, figureRepository, storage,
            mock(TaskAnswerRepository.class), mock(TaskGradeRepository.class),
            mock(PlatformTransactionManager.class), mock(SseHub.class));

        Section section = new Section();
        section.setExamId(EXAM);
        section.setPosition(2);
        section.setName("Teil B");
        when(sectionRepository.findByIdAndExamId(SECTION, EXAM)).thenReturn(Optional.of(section));
    }

    private SectionBlock block(String kind, int position, String content) {
        SectionBlock b = new SectionBlock();
        b.setExamId(EXAM);
        b.setSectionId(SECTION);
        b.setKind(kind);
        b.setPosition(position);
        b.setContent(content);
        return b;
    }

    private void withFigureImage(SectionBlock block, String path, byte[] bytes) {
        SectionFigure fig = new SectionFigure();
        fig.setBlockId(block.getId());
        fig.setStoragePath(path);
        fig.setSource("pdf");
        when(figureRepository.findByBlockIdOrderByPositionAsc(block.getId()))
            .thenReturn(List.of(fig));
        when(storage.download("exam-figures", path)).thenReturn(bytes);
    }

    /** Captures the request a solve would send for this context. */
    private AiProvider.ChatRequest requestFor(SolveCore.PromptContext ctx) {
        AiProvider provider = mock(AiProvider.class);
        when(provider.chat(any())).thenReturn(new AiProvider.ChatResponse(
            Map.of("answers", List.of()), "m", "p", null));
        core.askForAnswers(provider, "sys", ctx, List.of(), null);
        ArgumentCaptor<AiProvider.ChatRequest> req =
            ArgumentCaptor.forClass(AiProvider.ChatRequest.class);
        verify(provider).chat(req.capture());
        return req.getValue();
    }

    @Test
    void attachesFigureImagesLabelledToMatchTheirPlaceholder() {
        SectionBlock fig = block("figure", 1, "Abbildung 3 — Zustandsdiagramm");
        when(blockRepository.findBySectionIdOrderByPositionAsc(SECTION)).thenReturn(List.of(fig));
        withFigureImage(fig, "u/e/auto/x.png", new byte[]{1, 2, 3, 4});

        AiProvider.ChatRequest request = requestFor(core.loadContext(EXAM, SECTION, true));

        assertThat(request.userContent()).isInstanceOf(AiProvider.MultipartContent.class);
        List<AiProvider.ContentPart> parts =
            ((AiProvider.MultipartContent) request.userContent()).parts();
        assertThat(parts).hasSize(3);
        // Section position is 2, first figure -> "Figure 2.1", the same string the
        // prompt body uses for the placeholder.
        assertThat(parts.get(1)).isEqualTo(new AiProvider.TextPart("Figure 2.1:"));
        assertThat(parts.get(2)).isInstanceOf(AiProvider.ImageUrlPart.class);
        assertThat(((AiProvider.ImageUrlPart) parts.get(2)).url())
            .startsWith("data:image/png;base64,");
        assertThat(((AiProvider.TextPart) parts.get(0)).text()).contains("[Figure 2.1]");
    }

    @Test
    void sendsTextOnlyToASolverModelThatCannotSeeImages() {
        SectionBlock fig = block("figure", 1, "Abbildung 3");
        when(blockRepository.findBySectionIdOrderByPositionAsc(SECTION)).thenReturn(List.of(fig));
        withFigureImage(fig, "u/e/auto/x.png", new byte[]{1, 2, 3});

        // gemma-4-31b-it is a retired, text-only model an old exam may still be pinned to.
        boolean vision = SolverStrategies.resolve("gemma-4-31b-it").supportsVision();
        AiProvider.ChatRequest request = requestFor(core.loadContext(EXAM, SECTION, vision));

        assertThat(vision).isFalse();
        assertThat(request.userContent()).isInstanceOf(AiProvider.TextContent.class);
        verify(storage, never()).download(anyString(), anyString());
    }

    @Test
    void everyActiveSolverModelCanAcceptImages() {
        assertThat(SolverStrategies.all())
            .allSatisfy(s -> assertThat(s.supportsVision())
                .as("active solver %s", s.id()).isTrue());
    }

    @Test
    void stillSendsTextWhenAFigureBlockHasNoImageYet() {
        SectionBlock fig = block("figure", 1, "Abbildung 3 — Zustandsdiagramm");
        when(blockRepository.findBySectionIdOrderByPositionAsc(SECTION)).thenReturn(List.of(fig));
        when(figureRepository.findByBlockIdOrderByPositionAsc(fig.getId())).thenReturn(List.of());

        AiProvider.ChatRequest request = requestFor(core.loadContext(EXAM, SECTION, true));

        assertThat(request.userContent()).isInstanceOf(AiProvider.TextContent.class);
    }

    @Test
    void carriesTheFigureCaptionIntoThePromptText() {
        // The caption used to be dropped, leaving the model a bare token even when
        // the parser had extracted a perfectly good description.
        String prompt = Prompts.buildSectionUserPrompt(
            new Prompts.SectionPromptInfo(SECTION.toString(), 2, "Teil B"),
            List.of(new Prompts.BlockPromptInfo(
                UUID.randomUUID().toString(), SECTION.toString(), 1, "figure",
                "Abbildung 3 — Zustandsdiagramm des Automaten")),
            List.of());

        assertThat(prompt).contains("[Figure 2.1] Abbildung 3 — Zustandsdiagramm des Automaten");
    }

    @Test
    void skipsAFigureWhoseBytesAreMissingFromStorage() {
        SectionBlock fig = block("figure", 1, "Abbildung 3");
        when(blockRepository.findBySectionIdOrderByPositionAsc(SECTION)).thenReturn(List.of(fig));
        withFigureImage(fig, "u/e/auto/gone.png", null);

        AiProvider.ChatRequest request = requestFor(core.loadContext(EXAM, SECTION, true));

        assertThat(request.userContent()).isInstanceOf(AiProvider.TextContent.class);
    }

    @Test
    void numbersFiguresByBlockPositionEvenWhenAnEarlierOneHasNoImage() {
        SectionBlock first = block("figure", 1, "Abbildung 1");
        SectionBlock second = block("figure", 2, "Abbildung 2");
        when(blockRepository.findBySectionIdOrderByPositionAsc(SECTION))
            .thenReturn(List.of(first, second));
        when(figureRepository.findByBlockIdOrderByPositionAsc(first.getId())).thenReturn(List.of());
        withFigureImage(second, "u/e/auto/b.png", new byte[]{9, 9});

        SolveCore.PromptContext ctx = core.loadContext(EXAM, SECTION, true);

        // The image belongs to the SECOND figure block, so it must be labelled 2.2 —
        // labelling it 2.1 would point the model at the wrong placeholder.
        assertThat(ctx.figures()).hasSize(1);
        assertThat(ctx.figures().get(0).label()).isEqualTo("Figure 2.2");
    }

    @Test
    void capsHowManyImagesOneSectionMayAttach() {
        List<SectionBlock> blocks = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            SectionBlock b = block("figure", i + 1, "Abbildung " + (i + 1));
            blocks.add(b);
            withFigureImage(b, "u/e/auto/" + i + ".png", new byte[]{1, 2, 3});
        }
        when(blockRepository.findBySectionIdOrderByPositionAsc(SECTION)).thenReturn(blocks);

        SolveCore.PromptContext ctx = core.loadContext(EXAM, SECTION, true);

        assertThat(ctx.figures()).hasSize(8);
    }

    @Test
    void derivesTheMimeTypeFromTheStoredExtension() {
        SectionBlock fig = block("figure", 1, "Abbildung 1");
        when(blockRepository.findBySectionIdOrderByPositionAsc(SECTION)).thenReturn(List.of(fig));
        withFigureImage(fig, "u/e/manual.jpg", new byte[]{1, 2, 3});

        SolveCore.PromptContext ctx = core.loadContext(EXAM, SECTION, true);

        assertThat(ctx.figures().get(0).mimeType()).isEqualTo("image/jpeg");
    }

    @Test
    void readsNoFiguresForTheUnassignedBucket() {
        SolveCore.PromptContext ctx = core.loadContext(EXAM, null, true);

        assertThat(ctx.figures()).isEmpty();
        verify(storage, never()).download(anyString(), anyString());
    }

    @Test
    void ignoresContextBlocksWhenCollectingImages() {
        SectionBlock ctxBlock = block("context", 1, "Lesen Sie den folgenden Text.");
        when(blockRepository.findBySectionIdOrderByPositionAsc(SECTION)).thenReturn(List.of(ctxBlock));

        SolveCore.PromptContext ctx = core.loadContext(EXAM, SECTION, true);

        assertThat(ctx.figures()).isEmpty();
        verify(figureRepository, never()).findByBlockIdOrderByPositionAsc(eq(ctxBlock.getId()));
    }
}
