package app.solve;

import app.ai.AiExceptions;
import app.ai.AiProvider;
import app.error.ApiException;
import app.exam.Exam;
import app.section.Section;
import app.section.SectionBlock;
import app.task.Task;
import app.task.TaskAnswer;
import app.task.TaskOption;
import app.section.SectionBlockRepository;
import app.section.SectionFigure;
import app.section.SectionFigureRepository;
import app.section.SectionRepository;
import app.storage.StorageService;
import app.task.TaskAnswerRepository;
import app.grading.TaskGradeRepository;
import app.prompts.Prompts;
import app.sse.SseHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Machinery shared by {@link SolveSectionService} and {@link SolveTaskService}:
 * prompt-context assembly, the submit_answers tool call with transient-error
 * retry, answer mapping, and the atomic replace-answers-and-invalidate-grades
 * write. Solving one task is solving a section restricted to a one-task
 * subset, so both services delegate everything but their orchestration here.
 */
@Component
class SolveCore {

    private static final Logger log = LoggerFactory.getLogger(SolveCore.class);
    private static final String FIGURE_BUCKET = "exam-figures";

    /**
     * Caps on what one section may attach. Images are base64-inflated by a third
     * into a single request, so an exam with a screenshot on every block could
     * otherwise build a payload no provider will accept.
     */
    private static final int MAX_FIGURES = 8;
    private static final long MAX_FIGURE_BYTES = 12L * 1024 * 1024;

    private final SectionRepository sectionRepository;
    private final SectionBlockRepository sectionBlockRepository;
    private final SectionFigureRepository sectionFigureRepository;
    private final StorageService storage;
    private final TaskAnswerRepository taskAnswerRepository;
    private final TaskGradeRepository taskGradeRepository;
    private final TransactionTemplate txTemplate;
    private final SseHub sse;

    SolveCore(
        SectionRepository sectionRepository,
        SectionBlockRepository sectionBlockRepository,
        SectionFigureRepository sectionFigureRepository,
        StorageService storage,
        TaskAnswerRepository taskAnswerRepository,
        TaskGradeRepository taskGradeRepository,
        PlatformTransactionManager txManager,
        SseHub sse
    ) {
        this.sectionRepository = sectionRepository;
        this.sectionBlockRepository = sectionBlockRepository;
        this.sectionFigureRepository = sectionFigureRepository;
        this.storage = storage;
        this.taskAnswerRepository = taskAnswerRepository;
        this.taskGradeRepository = taskGradeRepository;
        this.txTemplate = new TransactionTemplate(txManager);
        this.sse = sse;
    }

    /** One figure image, labelled exactly as the section text refers to it. */
    record FigureImage(String label, byte[] bytes, String mimeType) {}

    record PromptContext(
        Prompts.SectionPromptInfo section,
        List<Prompts.BlockPromptInfo> blocks,
        List<FigureImage> figures
    ) {}

    record AskResult(List<Map<String, Object>> answers, String model) {}

    /**
     * Section + ordered context blocks for the prompt; the null section id is the
     * "_unassigned" bucket.
     *
     * <p>Figure bytes are loaded here, once per section, rather than in
     * {@link #askForAnswers}: that runs per task subset and retries once, so
     * loading there would re-read the same objects several times over.
     *
     * @param withFigures false for a solver model that cannot accept images, in
     *                    which case nothing is read from storage at all
     */
    PromptContext loadContext(UUID examId, UUID sectionId, boolean withFigures) {
        if (sectionId == null) {
            return new PromptContext(
                new Prompts.SectionPromptInfo("_unassigned", 0, "Unassigned"), List.of(), List.of());
        }
        Section sec = sectionRepository.findByIdAndExamId(sectionId, examId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Section not found"));
        List<SectionBlock> blockRows = sectionBlockRepository.findBySectionIdOrderByPositionAsc(sectionId);
        List<Prompts.BlockPromptInfo> blocks = new ArrayList<>();
        for (SectionBlock b : blockRows) {
            blocks.add(new Prompts.BlockPromptInfo(
                b.getId().toString(),
                b.getSectionId().toString(),
                b.getPosition(),
                b.getKind(),
                b.getContent()
            ));
        }
        return new PromptContext(
            new Prompts.SectionPromptInfo(sec.getId().toString(), sec.getPosition(), sec.getName()),
            blocks,
            withFigures ? loadFigures(sec.getPosition(), blockRows) : List.of()
        );
    }

    /**
     * Resolves each figure block's image, numbered the way the prompt numbers it.
     * Blocks whose image is missing simply contribute nothing — the section text
     * still carries their placeholder and caption.
     */
    private List<FigureImage> loadFigures(int sectionPosition, List<SectionBlock> blockRows) {
        List<SectionBlock> figureBlocks = new ArrayList<>(blockRows.stream()
            .filter(b -> "figure".equals(b.getKind()))
            .toList());
        figureBlocks.sort(Comparator.comparingInt(SectionBlock::getPosition));

        List<FigureImage> out = new ArrayList<>();
        long budget = MAX_FIGURE_BYTES;
        int number = 0;
        for (SectionBlock block : figureBlocks) {
            number += 1;
            if (out.size() >= MAX_FIGURES) break;
            List<SectionFigure> figures =
                sectionFigureRepository.findByBlockIdOrderByPositionAsc(block.getId());
            if (figures.isEmpty()) continue;
            SectionFigure figure = figures.get(0);
            byte[] bytes;
            try {
                bytes = storage.download(FIGURE_BUCKET, figure.getStoragePath());
            } catch (RuntimeException e) {
                log.warn("solve: figure {} unreadable: {}", figure.getId(), e.toString());
                continue;
            }
            if (bytes == null || bytes.length == 0 || bytes.length > budget) continue;
            budget -= bytes.length;
            out.add(new FigureImage(
                Prompts.figureLabel(sectionPosition, number), bytes, mimeType(figure.getStoragePath())));
        }
        return out;
    }

    private static String mimeType(String path) {
        String lower = path == null ? "" : path.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        return "image/png";
    }

    String systemPrompt(Exam exam) {
        return Prompts.buildSystemPrompt(new Prompts.ExamPromptInfo(
            exam.getId().toString(),
            exam.getTitle(),
            exam.getCourse()
        ));
    }

    Prompts.TaskPromptInfo toTaskInfo(Task t) {
        List<Prompts.TaskOptionPromptInfo> opts = null;
        if (t.getOptions() != null) {
            opts = new ArrayList<>();
            for (TaskOption o : t.getOptions()) {
                opts.add(new Prompts.TaskOptionPromptInfo(o.id(), o.text()));
            }
        }
        return new Prompts.TaskPromptInfo(
            t.getId().toString(),
            t.getSectionId() == null ? null : t.getSectionId().toString(),
            t.getPosition(),
            t.getType(),
            t.getPrompt(),
            opts,
            t.getPoints() == null ? null : t.getPoints().doubleValue()
        );
    }

    /**
     * One submit_answers tool call for the given task subset, with a single
     * retry on transient failures (payment-required always fails fast).
     */
    @SuppressWarnings("unchecked")
    AskResult askForAnswers(
        AiProvider provider, String systemPrompt,
        PromptContext ctx, List<Prompts.TaskPromptInfo> subset, String extraInstruction
    ) {
        String base = Prompts.buildSectionUserPrompt(ctx.section(), ctx.blocks(), subset);
        String userPrompt = extraInstruction == null ? base : base + "\n\n" + extraInstruction;
        AiProvider.UserContent content = userContent(userPrompt, ctx.figures());
        RuntimeException lastErr = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                AiProvider.ChatResponse res = provider.chat(new AiProvider.ChatRequest(
                    systemPrompt,
                    content,
                    new AiProvider.Tool(
                        "submit_answers",
                        "Submit answers for every task in this section.",
                        Prompts.submitAnswersSchema()
                    )
                ));
                Object raw = res.toolArgs().get("answers");
                List<Map<String, Object>> answers = (raw instanceof List<?> l)
                    ? new ArrayList<>((List<Map<String, Object>>) l)
                    : List.of();
                return new AskResult(answers, res.model());
            } catch (AiExceptions.PaymentRequiredException e) {
                throw e;
            } catch (RuntimeException e) {
                lastErr = e;
                if (!AiExceptions.isTransient(e) || attempt == 1) break;
                try { Thread.sleep(500L * (attempt + 1)); } catch (InterruptedException ignored) {}
            }
        }
        throw lastErr == null ? new RuntimeException("Unknown provider error") : lastErr;
    }

    /**
     * Map one raw model answer onto a {@link TaskAnswer} row, keeping only
     * option ids that actually exist on the task.
     */
    TaskAnswer toAnswerRow(
        Prompts.TaskPromptInfo task, UUID examId,
        Map<String, Object> answer, String providerName, String model
    ) {
        boolean isChoice = !"text".equals(task.type());
        Set<String> validOptionIds = new HashSet<>();
        if (task.options() != null) {
            for (Prompts.TaskOptionPromptInfo o : task.options()) validOptionIds.add(o.id());
        }
        List<UUID> selected = new ArrayList<>();
        Object rawSelected = answer.get("selected_option_ids");
        if (isChoice && rawSelected instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s && validOptionIds.contains(s)) selected.add(UUID.fromString(s));
            }
        }

        TaskAnswer row = new TaskAnswer();
        row.setTaskId(UUID.fromString(task.id()));
        row.setExamId(examId);
        row.setSelectedOptionIds(selected);
        row.setAnswerText(isChoice ? null : asString(answer.get("answer_text")));
        row.setReasoning(asString(answer.get("reasoning")));
        row.setProvider(providerName);
        row.setModel(model);
        return row;
    }

    /**
     * Replace existing answers for the given tasks + invalidate their auto-grades
     * atomically, so a mid-write failure can't leave deleted-but-not-rewritten
     * answers. Emits a progress event afterwards (SseHub never throws).
     */
    void replaceAnswers(UUID examId, List<TaskAnswer> rows) {
        if (rows.isEmpty()) return;
        List<UUID> taskIds = new ArrayList<>();
        for (TaskAnswer r : rows) taskIds.add(r.getTaskId());
        txTemplate.executeWithoutResult(s -> {
            taskAnswerRepository.deleteByTaskIdIn(taskIds);
            taskAnswerRepository.saveAll(rows);
            taskGradeRepository.deleteByTaskIdInAndAutoGradedTrue(taskIds);
        });
        sse.progress(examId);
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }
    /**
     * The prompt, followed by each figure image immediately after a text part
     * naming it. The interleaved label is what lets the model tie an image to the
     * matching [Figure x.y] placeholder in the section text.
     */
    private static AiProvider.UserContent userContent(String prompt, List<FigureImage> figures) {
        if (figures == null || figures.isEmpty()) {
            return new AiProvider.TextContent(prompt);
        }
        List<AiProvider.ContentPart> parts = new ArrayList<>();
        parts.add(new AiProvider.TextPart(prompt));
        for (FigureImage f : figures) {
            parts.add(new AiProvider.TextPart(f.label() + ":"));
            parts.add(new AiProvider.ImageUrlPart(
                "data:" + f.mimeType() + ";base64," + Base64.getEncoder().encodeToString(f.bytes())));
        }
        return new AiProvider.MultipartContent(parts);
    }

}
