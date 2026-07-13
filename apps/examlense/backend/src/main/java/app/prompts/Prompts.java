package app.prompts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Java port of supabase/functions/_shared/prompts.ts. Keep behavior in sync
 * with the TS version — the solver edge functions and this Spring port share
 * the same JSON schema and prompt shape.
 */
public final class Prompts {

    private Prompts() {}

    public record ExamPromptInfo(String id, String title, String course, String language) {}
    public record SectionPromptInfo(String id, int position, String name) {}
    public record BlockPromptInfo(String id, String sectionId, int position, String kind, String content) {}
    public record TaskOptionPromptInfo(String id, String text) {}
    public record TaskPromptInfo(
        String id,
        String sectionId,
        int position,
        String type,                // single_choice | multiple_choice | text
        String prompt,
        List<TaskOptionPromptInfo> options, // nullable
        Double points               // nullable
    ) {}

    /** JSON schema the solver LLM must call submit_answers with (immutable — built once). */
    private static final Map<String, Object> SUBMIT_ANSWERS_SCHEMA = buildSubmitAnswersSchema();

    public static Map<String, Object> submitAnswersSchema() {
        return SUBMIT_ANSWERS_SCHEMA;
    }

    private static Map<String, Object> buildSubmitAnswersSchema() {
        Map<String, Object> taskIdProp = Map.of("type", "string", "description", "The task id from the prompt.");
        Map<String, Object> typeProp = Map.of(
            "type", "string",
            "enum", List.of("single_choice", "multiple_choice", "text")
        );
        Map<String, Object> selectedOptionIdsProp = Map.of(
            "type", "array",
            "items", Map.of("type", "string"),
            "description",
            "Option ids picked for choice tasks. Exactly one for single_choice, zero or more for multiple_choice. Empty/omitted for text tasks."
        );
        Map<String, Object> answerTextProp = Map.of(
            "type", "string",
            "description",
            "Concise free-text answer. Only the content that would earn points — no restating the question, no filler."
        );
        Map<String, Object> reasoningProp = Map.of(
            "type", "string",
            "description", "Optional one-line rationale, used internally for grading."
        );

        Map<String, Object> answerItem = new LinkedHashMap<>();
        answerItem.put("type", "object");
        answerItem.put("additionalProperties", false);
        Map<String, Object> answerItemProps = new LinkedHashMap<>();
        answerItemProps.put("task_id", taskIdProp);
        answerItemProps.put("type", typeProp);
        answerItemProps.put("selected_option_ids", selectedOptionIdsProp);
        answerItemProps.put("answer_text", answerTextProp);
        answerItemProps.put("reasoning", reasoningProp);
        answerItem.put("properties", answerItemProps);
        answerItem.put("required", List.of("task_id", "type"));

        Map<String, Object> answersProp = Map.of(
            "type", "array",
            "items", answerItem
        );

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("properties", Map.of("answers", answersProp));
        schema.put("required", List.of("answers"));
        return schema;
    }

    private static String languageName(String code) {
        if ("de".equals(code)) return "German";
        if ("en".equals(code)) return "English";
        return "the same language as the questions";
    }

    public static String buildSystemPrompt(ExamPromptInfo exam) {
        String lang = languageName(exam.language());
        String title = exam.title() == null ? "Untitled" : exam.title();
        String courseSuffix = exam.course() != null ? " · Course: " + exam.course() : "";
        return String.join("\n",
            "You are an expert taking a university exam.",
            "Exam: \"" + title + "\"" + courseSuffix + ".",
            "Respond in " + lang + ".",
            "You will be given one section of the exam with optional context paragraphs and a list of tasks.",
            "Answer every task. Use the submit_answers tool — never reply in plain text.",
            "",
            "For single_choice: pick exactly one option id.",
            "For multiple_choice: pick every option id you believe is correct (zero or more).",
            "For text: write the most concise answer that would earn full credit. No restating the question, no apologies, no meta-commentary. Equations, code, or short prose only."
        );
    }

    public static String buildSectionUserPrompt(
        SectionPromptInfo section,
        List<BlockPromptInfo> blocks,
        List<TaskPromptInfo> tasks
    ) {
        List<String> lines = new ArrayList<>();
        String secName = section.name() == null ? "" : section.name().trim();
        lines.add("Section: " + (secName.isEmpty() ? "(untitled)" : secName));
        lines.add("");

        List<BlockPromptInfo> sortedBlocks = new ArrayList<>(blocks);
        sortedBlocks.sort(Comparator.comparingInt(BlockPromptInfo::position));
        sortedBlocks.removeIf(b ->
            "context".equals(b.kind()) && (b.content() == null || b.content().trim().isEmpty())
        );

        lines.add("Context:");
        if (sortedBlocks.isEmpty()) {
            lines.add("(none)");
        } else {
            int figureCount = 0;
            for (BlockPromptInfo b : sortedBlocks) {
                if ("context".equals(b.kind())) {
                    lines.add("- " + b.content().trim());
                } else {
                    figureCount += 1;
                    lines.add("- [Figure " + section.position() + "." + figureCount + "]");
                }
            }
        }
        lines.add("");

        lines.add("Tasks:");
        List<TaskPromptInfo> sortedTasks = new ArrayList<>(tasks);
        sortedTasks.sort(Comparator.comparingInt(TaskPromptInfo::position));
        for (int idx = 0; idx < sortedTasks.size(); idx++) {
            TaskPromptInfo t = sortedTasks.get(idx);
            List<String> metaParts = new ArrayList<>();
            metaParts.add(t.type());
            metaParts.add("id=" + t.id());
            if (t.points() != null) metaParts.add("points=" + stripTrailingZero(t.points()));
            lines.add("[t" + (idx + 1) + "] (" + String.join(", ", metaParts) + ") " + t.prompt().trim());
            if (!"text".equals(t.type()) && t.options() != null) {
                for (int i = 0; i < t.options().size(); i++) {
                    TaskOptionPromptInfo opt = t.options().get(i);
                    char letter = (char) ('a' + i);
                    lines.add("  " + letter + ") id=" + opt.id() + ": " + opt.text());
                }
            }
        }
        return String.join("\n", lines);
    }

    private static String stripTrailingZero(double d) {
        if (d == Math.floor(d) && !Double.isInfinite(d)) {
            return Long.toString((long) d);
        }
        return Double.toString(d);
    }
}
