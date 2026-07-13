package app.solve;

import app.ai.AiExceptions;
import app.ai.AiProvider;
import app.error.ApiException;
import app.persistence.entity.Exam;
import app.persistence.entity.Section;
import app.persistence.entity.SectionBlock;
import app.persistence.entity.Task;
import app.persistence.entity.TaskAnswer;
import app.persistence.entity.TaskOption;
import app.persistence.repository.SectionBlockRepository;
import app.persistence.repository.SectionRepository;
import app.persistence.repository.TaskAnswerRepository;
import app.persistence.repository.TaskGradeRepository;
import app.prompts.Prompts;
import app.sse.SseHub;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
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

    private final SectionRepository sectionRepository;
    private final SectionBlockRepository sectionBlockRepository;
    private final TaskAnswerRepository taskAnswerRepository;
    private final TaskGradeRepository taskGradeRepository;
    private final TransactionTemplate txTemplate;
    private final SseHub sse;

    SolveCore(
        SectionRepository sectionRepository,
        SectionBlockRepository sectionBlockRepository,
        TaskAnswerRepository taskAnswerRepository,
        TaskGradeRepository taskGradeRepository,
        PlatformTransactionManager txManager,
        SseHub sse
    ) {
        this.sectionRepository = sectionRepository;
        this.sectionBlockRepository = sectionBlockRepository;
        this.taskAnswerRepository = taskAnswerRepository;
        this.taskGradeRepository = taskGradeRepository;
        this.txTemplate = new TransactionTemplate(txManager);
        this.sse = sse;
    }

    record PromptContext(Prompts.SectionPromptInfo section, List<Prompts.BlockPromptInfo> blocks) {}

    record AskResult(List<Map<String, Object>> answers, String model) {}

    /** Section + ordered context blocks for the prompt; the null section id is the "_unassigned" bucket. */
    PromptContext loadContext(UUID examId, UUID sectionId) {
        if (sectionId == null) {
            return new PromptContext(new Prompts.SectionPromptInfo("_unassigned", 0, "Unassigned"), List.of());
        }
        Section sec = sectionRepository.findByIdAndExamId(sectionId, examId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Section not found"));
        List<Prompts.BlockPromptInfo> blocks = new ArrayList<>();
        for (SectionBlock b : sectionBlockRepository.findBySectionIdOrderByPositionAsc(sectionId)) {
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
            blocks
        );
    }

    String systemPrompt(Exam exam) {
        return Prompts.buildSystemPrompt(new Prompts.ExamPromptInfo(
            exam.getId().toString(),
            exam.getTitle(),
            exam.getCourse(),
            exam.getLanguage()
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
        RuntimeException lastErr = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                AiProvider.ChatResponse res = provider.chat(new AiProvider.ChatRequest(
                    systemPrompt,
                    new AiProvider.TextContent(userPrompt),
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
}
