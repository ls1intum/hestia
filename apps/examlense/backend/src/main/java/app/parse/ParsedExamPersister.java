package app.parse;

import app.exam.Exam;
import app.section.Section;
import app.section.SectionBlock;
import app.task.Task;
import app.task.TaskOption;
import app.exam.ExamRepository;
import app.section.SectionBlockRepository;
import app.section.SectionRepository;
import app.task.TaskRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns one parsed-exam LLM payload into rows. Split from the pipeline
 * orchestrator ({@link ParseExamService}) so the trickiest logic — defensive
 * normalization of nondeterministic model output — is testable in isolation.
 *
 * Write policy:
 *   - The structural write (cleanup of any prior parse artifacts, sections,
 *     intro blocks, tasks) is one transaction: a partial failure can't leave
 *     sections without their tasks, and a re-parse can't duplicate rows.
 *   - Decorative figure/context blocks are best-effort in a separate step —
 *     losing them must not fail the parse.
 *   - Finalizing the exam re-checks the status so a user cancel that landed
 *     mid-persist wins (the exam stays failed).
 */
@Component
class ParsedExamPersister {

    private static final Logger log = LoggerFactory.getLogger(ParsedExamPersister.class);
    private static final Set<String> VALID_TASK_TYPES = Set.of("single_choice", "multiple_choice", "text");

    private final ExamRepository examRepository;
    private final SectionRepository sectionRepository;
    private final TaskRepository taskRepository;
    private final SectionBlockRepository sectionBlockRepository;
    private final TransactionTemplate txTemplate;
    private final ParseProgress progress;

    ParsedExamPersister(
        ExamRepository examRepository,
        SectionRepository sectionRepository,
        TaskRepository taskRepository,
        SectionBlockRepository sectionBlockRepository,
        PlatformTransactionManager txManager,
        ParseProgress progress
    ) {
        this.examRepository = examRepository;
        this.sectionRepository = sectionRepository;
        this.taskRepository = taskRepository;
        this.sectionBlockRepository = sectionBlockRepository;
        this.txTemplate = new TransactionTemplate(txManager);
        this.progress = progress;
    }

    /** True if the exam is gone or has left the `parsing` state (e.g. user cancelled → failed). */
    boolean isNoLongerParsing(UUID examId) {
        return examRepository.findById(examId)
            .map(e -> !"parsing".equals(e.getStatus()))
            .orElse(true);
    }

    @SuppressWarnings("unchecked")
    boolean persist(
        ParseAttempt attempt,
        Map<String, Object> parsed,
        List<Map<String, Object>> tasks,
        String languageHint
    ) {
        UUID examId = attempt.examId;

        // -- Cancellation guard: the LLM call runs unattended and can return long
        // after the user cancelled (which flips the exam to `failed`). Don't
        // resurrect a cancelled/deleted exam by writing results — bail before
        // inserting anything. The finalize step re-checks for the tiny window
        // where a cancel lands mid-persist.
        if (isNoLongerParsing(examId)) {
            log.info("parse-exam-pdf[{}] no longer parsing before persist — skipping (cancelled?)", examId);
            attempt.error = "Cancelled before results were saved.";
            return false;
        }

        // -- Defensive fill: every task must end up in a section. The prompt
        // tells the LLM to always emit one, but model output is non-deterministic.
        fillMissingSections(parsed, tasks, languageHint);

        // -- Sections (drop ones with no tasks)
        Set<String> taskSectionNames = new HashSet<>();
        for (Map<String, Object> t : tasks) {
            Object s = t.get("section");
            if (s instanceof String ss && !ss.trim().isEmpty()) taskSectionNames.add(ss.trim());
        }

        List<Map<String, String>> sectionList = collectSections(parsed, tasks, taskSectionNames);

        // App-assigned UUIDs let us build the name→id map directly — no re-fetch.
        Map<String, UUID> sectionIdByName = new HashMap<>();
        Map<String, String> sectionDescByName = new HashMap<>();
        List<Section> sectionRows = new ArrayList<>();
        for (int i = 0; i < sectionList.size(); i++) {
            Section s = new Section();
            s.setExamId(examId);
            s.setPosition(i + 1);
            s.setName(sectionList.get(i).get("name"));
            sectionRows.add(s);
            sectionIdByName.put(s.getName(), s.getId());
            sectionDescByName.put(s.getName(), sectionList.get(i).get("description"));
        }

        // Intro context blocks (position 0) for sections with a description
        List<SectionBlock> introBlocks = new ArrayList<>();
        for (Map<String, String> s : sectionList) {
            String desc = s.get("description");
            if (desc == null || desc.trim().isEmpty()) continue;
            UUID sid = sectionIdByName.get(s.get("name"));
            if (sid == null) continue;
            introBlocks.add(blockRow(examId, sid, 0, desc.trim(), "context"));
        }

        List<Task> taskRows = buildTaskRows(examId, tasks, sectionIdByName);

        // -- Structural write: one transaction. Deletes any artifacts from a
        // previous parse first, so a re-parse (or a failed attempt's leftovers)
        // can never produce duplicate sections/tasks.
        try {
            txTemplate.executeWithoutResult(status -> {
                taskRepository.deleteByExamId(examId);
                sectionRepository.deleteByExamId(examId); // blocks cascade via FK
                if (!sectionRows.isEmpty()) sectionRepository.saveAll(sectionRows);
                if (!introBlocks.isEmpty()) sectionBlockRepository.saveAll(introBlocks);
                taskRepository.saveAll(taskRows);
            });
        } catch (Exception e) {
            log.error("parse-exam-pdf[{}] structural insert failed", examId, e);
            fail(attempt, "Failed to save parsed tasks.");
            return false;
        }

        // -- Figures + mid-section context blocks: best-effort, decorative.
        persistDecorativeBlocks(examId, parsed, taskRows, sectionIdByName, sectionDescByName);

        // -- Finalize exam
        return finalizeExam(attempt, parsed, languageHint);
    }

    private List<Map<String, String>> collectSections(
        Map<String, Object> parsed, List<Map<String, Object>> tasks, Set<String> taskSectionNames
    ) {
        List<Map<String, String>> sectionList = new ArrayList<>(); // {name, description}
        Set<String> seen = new LinkedHashSet<>();
        java.util.function.BiConsumer<String, String> pushSection = (name, desc) -> {
            String key = name == null ? "" : name.trim();
            if (key.isEmpty() || seen.contains(key)) return;
            if (!taskSectionNames.contains(key)) return;
            seen.add(key);
            Map<String, String> row = new HashMap<>();
            row.put("name", key);
            row.put("description", desc);
            sectionList.add(row);
        };
        if (parsed.get("sections") instanceof List<?> sl) {
            for (Object o : sl) {
                if (o instanceof Map<?, ?> m && m.get("name") instanceof String n) {
                    pushSection.accept(n, m.get("description") instanceof String d ? d : null);
                }
            }
        }
        for (Map<String, Object> t : tasks) {
            if (t.get("section") instanceof String ss) pushSection.accept(ss, null);
        }
        return sectionList;
    }

    private List<Task> buildTaskRows(UUID examId, List<Map<String, Object>> tasks, Map<String, UUID> sectionIdByName) {
        Map<UUID, Integer> posBySection = new HashMap<>();
        List<Task> taskRows = new ArrayList<>();
        for (int i = 0; i < tasks.size(); i++) {
            Map<String, Object> t = tasks.get(i);
            String sectionKey = (t.get("section") instanceof String ss && !ss.trim().isEmpty()) ? ss.trim() : null;
            UUID sectionId = sectionKey == null ? null : sectionIdByName.get(sectionKey);
            int finalPos;
            if (sectionId != null) {
                finalPos = posBySection.merge(sectionId, 1, Integer::sum);
            } else {
                finalPos = i + 1;
            }

            Task task = new Task();
            task.setExamId(examId);
            task.setPosition(finalPos);
            task.setSection(t.get("section") instanceof String s ? s : null);
            task.setSectionId(sectionId);
            task.setPrompt(t.get("prompt") instanceof String pr ? pr : "");

            List<TaskOption> mapped = null;
            if (t.get("options") instanceof List<?> ol) {
                mapped = new ArrayList<>();
                for (Object o : ol) {
                    if (o instanceof Map<?, ?> m) {
                        mapped.add(new TaskOption(
                            UUID.randomUUID().toString(),
                            m.get("text") == null ? "" : m.get("text").toString(),
                            Boolean.TRUE.equals(m.get("is_correct"))
                        ));
                    }
                }
            }
            task.setOptions(mapped);
            // tasks.type is NOT NULL with a fixed enum, but model output isn't guaranteed
            // to comply. A null/garbled type must not fail the whole batch insert (which
            // would lose every other parsed task). Normalize: keep valid values, infer
            // choice types from options, else fall back to free-text.
            task.setType(normalizeTaskType(t.get("type"), mapped));
            task.setReferenceAnswer(null);
            task.setPoints(toBigDecimal(t.get("points")));
            task.setParseConfidence("medium");
            taskRows.add(task);
        }
        return taskRows;
    }

    /**
     * Figures and mid-section context blocks share one persistence shape:
     * resolve the section, map after_task_index → position, insert with a
     * swallowing try/catch (blocks are decorative — tasks are already safe).
     */
    private void persistDecorativeBlocks(
        UUID examId,
        Map<String, Object> parsed,
        List<Task> taskRows,
        Map<String, UUID> sectionIdByName,
        Map<String, String> sectionDescByName
    ) {
        Object figuresObj = parsed.get("figures");
        Object ctxObj = parsed.get("context_blocks");
        boolean hasFigures = figuresObj instanceof List<?> fl && !fl.isEmpty();
        boolean hasContext = ctxObj instanceof List<?> cl && !cl.isEmpty();
        if (!hasFigures && !hasContext) return;

        // Build after_task_index -> position map from the tasks we just created.
        Map<UUID, List<Integer>> positionsBySection = new HashMap<>();
        for (Task te : taskRows) {
            UUID sid = te.getSectionId();
            if (sid == null) continue;
            positionsBySection.computeIfAbsent(sid, k -> new ArrayList<>()).add(te.getPosition());
        }
        for (List<Integer> v : positionsBySection.values()) Collections.sort(v);

        if (hasFigures) {
            List<SectionBlock> rows = new ArrayList<>();
            for (Object o : (List<?>) figuresObj) {
                if (!(o instanceof Map<?, ?> fig)) continue;
                List<String> pieces = new ArrayList<>();
                for (String k : List.of("label", "caption")) {
                    if (fig.get(k) instanceof String sv && !sv.trim().isEmpty()) pieces.add(sv.trim());
                }
                SectionBlock b = decorativeBlock(examId, fig, "figure",
                    pieces.isEmpty() ? "" : String.join(" — ", pieces),
                    sectionIdByName, positionsBySection);
                if (b != null) rows.add(b);
            }
            saveBlocksBestEffort(examId, rows, "figure");
        }

        if (hasContext) {
            List<SectionBlock> rows = new ArrayList<>();
            for (Object o : (List<?>) ctxObj) {
                if (!(o instanceof Map<?, ?> ctx)) continue;
                String content = ctx.get("content") instanceof String cs ? cs.trim() : "";
                if (content.isEmpty()) continue;
                Integer idx = (ctx.get("after_task_index") instanceof Number n) ? n.intValue() : null;
                if (idx == null || idx < 0) {
                    // Already covered by the section's intro description? Skip.
                    String sectionKey = (ctx.get("section") instanceof String s && !s.trim().isEmpty()) ? s.trim() : null;
                    String existing = sectionKey == null ? null : sectionDescByName.get(sectionKey);
                    if (existing != null && !existing.trim().isEmpty()) continue;
                }
                SectionBlock b = decorativeBlock(examId, ctx, "context", content,
                    sectionIdByName, positionsBySection);
                if (b != null) rows.add(b);
            }
            saveBlocksBestEffort(examId, rows, "context");
        }
    }

    /** Resolve one raw figure/context entry to a block row, or null when its section is unknown. */
    private SectionBlock decorativeBlock(
        UUID examId, Map<?, ?> src, String kind, String content,
        Map<String, UUID> sectionIdByName, Map<UUID, List<Integer>> positionsBySection
    ) {
        String sectionKey = (src.get("section") instanceof String s && !s.trim().isEmpty()) ? s.trim() : null;
        if (sectionKey == null) return null;
        UUID sid = sectionIdByName.get(sectionKey);
        if (sid == null) return null;
        Integer idx = (src.get("after_task_index") instanceof Number n) ? n.intValue() : null;
        int pos = resolvePosition(positionsBySection.get(sid), idx);
        return blockRow(examId, sid, pos, content, kind);
    }

    private void saveBlocksBestEffort(UUID examId, List<SectionBlock> rows, String kind) {
        if (rows.isEmpty()) return;
        try {
            sectionBlockRepository.saveAll(rows);
        } catch (Exception e) {
            log.error("parse-exam-pdf[{}] {} blocks insert failed", examId, kind, e);
        }
    }

    private static SectionBlock blockRow(UUID examId, UUID sectionId, int position, String content, String kind) {
        SectionBlock b = new SectionBlock();
        b.setExamId(examId);
        b.setSectionId(sectionId);
        b.setPosition(position);
        b.setContent(content);
        b.setKind(kind);
        return b;
    }

    private boolean finalizeExam(ParseAttempt attempt, Map<String, Object> parsed, String languageHint) {
        UUID examId = attempt.examId;
        try {
            Exam exam = examRepository.findById(examId).orElse(null);
            if (exam == null) {
                fail(attempt, "Failed to finalize parsed exam.");
                return false;
            }
            // Cancelled during the insert window above — leave it failed, don't flip to draft.
            if (!"parsing".equals(exam.getStatus())) {
                log.info("parse-exam-pdf[{}] cancelled mid-persist (status={}) — not finalizing",
                    examId, exam.getStatus());
                attempt.error = "Cancelled before results were saved.";
                return false;
            }
            if (parsed.get("title") instanceof String ts && !ts.isEmpty()) exam.setTitle(ts);
            exam.setCourse(asString(parsed.get("course")));
            exam.setSemester(asString(parsed.get("semester")));
            Object lang = parsed.get("detected_language");
            exam.setLanguage(lang instanceof String ls ? ls : (languageHint != null ? languageHint : "en"));
            exam.setStatus("draft");
            exam.setParseError(null);
            exam.setParsePhase(null);
            examRepository.save(exam);
            progress.notifyExam(examId);
        } catch (Exception e) {
            log.error("parse-exam-pdf[{}] exam update failed", examId, e);
            fail(attempt, "Failed to finalize parsed exam.");
            return false;
        }
        return true;
    }

    private void fail(ParseAttempt attempt, String message) {
        attempt.error = message;
        progress.fail(attempt.examId, message);
    }

    // -- Defensive normalization of model output (package-private for tests) --

    /**
     * Two-pass defensive fill so the UI never has to show "Unassigned tasks":
     *   1. Carry-forward — for every task with a null/blank section, copy the
     *      most-recently-seen non-blank section. Keeps stray nulls next to
     *      their visual neighbours.
     *   2. Synthesize — if no prior section exists (tasks before any section,
     *      or fully-flat exam), invent one from the exam title / course / a
     *      language-appropriate default, then backfill every still-blank task.
     * Edits the task maps in place.
     */
    static void fillMissingSections(
        Map<String, Object> parsed,
        List<Map<String, Object>> tasks,
        String languageHint
    ) {
        String lastSeen = null;
        boolean anyMissing = false;
        for (Map<String, Object> t : tasks) {
            Object raw = t.get("section");
            String s = (raw instanceof String ss) ? ss.trim() : "";
            if (!s.isEmpty()) {
                lastSeen = s;
                t.put("section", s);
            } else if (lastSeen != null) {
                t.put("section", lastSeen);
            } else {
                anyMissing = true;
            }
        }
        if (!anyMissing) return;

        // Need a synthesized name for the still-blank early tasks.
        String synth = pickSyntheticSection(parsed, languageHint);
        for (Map<String, Object> t : tasks) {
            Object raw = t.get("section");
            String s = (raw instanceof String ss) ? ss.trim() : "";
            if (s.isEmpty()) t.put("section", synth);
        }
    }

    static String pickSyntheticSection(Map<String, Object> parsed, String languageHint) {
        if (parsed.get("title") instanceof String t && !t.trim().isEmpty()) return t.trim();
        if (parsed.get("course") instanceof String c && !c.trim().isEmpty()) return c.trim();
        String lang = parsed.get("detected_language") instanceof String d ? d : languageHint;
        return "de".equalsIgnoreCase(lang) ? "Aufgaben" : "Tasks";
    }

    /**
     * Map the model's {@code after_task_index} (0-based index into the section's
     * tasks) onto a block position: the position of the NEXT task, or one past
     * the last task when the index points at it, or 0 (top) when out of range.
     */
    static int resolvePosition(List<Integer> sectionTaskPositions, Integer afterTaskIndex) {
        if (sectionTaskPositions == null || sectionTaskPositions.isEmpty()) return 0;
        if (afterTaskIndex == null || afterTaskIndex < 0) return 0;
        int idx = afterTaskIndex;
        if (idx + 1 < sectionTaskPositions.size()) return sectionTaskPositions.get(idx + 1);
        if (idx < sectionTaskPositions.size()) return sectionTaskPositions.get(idx) + 1;
        return 0;
    }

    /**
     * Coerce a task type to a valid, non-null enum value. Models occasionally omit or
     * garble {@code type}; since {@code tasks.type} is NOT NULL, a bad value would fail
     * the whole batch insert. Keep valid values; otherwise infer from options (a choice
     * task) or default to free-text.
     */
    static String normalizeTaskType(Object rawType, List<TaskOption> options) {
        if (rawType instanceof String ty && VALID_TASK_TYPES.contains(ty)) return ty;
        if (options != null && !options.isEmpty()) {
            long correct = options.stream().filter(TaskOption::isCorrect).count();
            return correct > 1 ? "multiple_choice" : "single_choice";
        }
        return "text";
    }

    private static BigDecimal toBigDecimal(Object v) {
        return v instanceof Number n ? new BigDecimal(n.toString()) : null;
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }
}
