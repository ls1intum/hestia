package app.parse;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * System prompt + JSON schema for the `extract_exam` tool call. Kept
 * verbatim from supabase/functions/parse-exam-pdf/index.ts — any change
 * here must mirror the edge function until that function is decommissioned.
 */
public final class ParseExamPrompts {

    private ParseExamPrompts() {}

    public static final String TOOL_NAME = "extract_exam";
    public static final String TOOL_DESCRIPTION =
        "Extract structured exam metadata and tasks from the provided exam PDF.";

    public static final String SYSTEM_PROMPT = """
        Extract every task from the university exam PDF, preserving the original language verbatim.

        Your job is STRICTLY transcription: faithfully extract what is printed on the page. NEVER solve tasks and NEVER infer answers from your own subject knowledge — the only answer information you may record is a visible answer-key mark on a choice option (see below). Be literal and complete but not verbose: never restate, summarize, or annotate content beyond the schema fields.

        DO NOT extract or include in any field (skip these entirely):
        - General instructions or notices for students: "Bearbeitungshinweise", "Hinweise zur Bearbeitung", "Hilfsmittel", allowed materials, time/duration, grading scheme overview, "please write legibly", signature lines, name / matriculation number / date fields.
        - Cover-page boilerplate beyond title/course (which go to dedicated top-level fields, not into any section).
        - Blank pages, "extra space for your answer", "Reserveseite", "Notizen", scratch / draft pages, typically at the end of the PDF.
        - Page headers, footers, page numbers, watermarks, university/department branding.

        For each task:
        - type: "single_choice" (exactly one correct answer), "multiple_choice" (one or more correct), or "text" (free-text/open/essay/calculation).
        - For SC/MC: extract every option with text. Set is_correct ONLY if the PDF visibly indicates the answer (a check mark, highlight, ticked box, "x" mark, shading, or an explicit answer-key page that pairs this question with a marked option). If no such visible indication exists, is_correct MUST be false for EVERY option of this task.
        - Option text is rendered on ONE line. Never put multi-line content (fenced code blocks, multi-line listings, tables) into an option's text. If the options refer to a code listing, put the listing into the task prompt (or a context block) as a fenced code block and keep each option a short single line; short code fragments inside an option use inline backticks.
        - section: the nearest section header (e.g. "Part A"). REQUIRED and non-empty for EVERY task. If the PDF has no explicit section headings (a flat question list), invent ONE section named after the exam (use the exam title, the course name, or a generic word like "Tasks" / "Aufgaben" matching the document language) and assign every task to it. Never emit a task with section: null or "".
        - points: the explicit point value if shown.
        - prompt: the question text ONLY. Do NOT include the subtask enumerator/label that prefixes it in the PDF (e.g. "a)", "b.", "(c)", "1.", "1)", "i.", "ii)", "A)"). Strip that leading label and any trailing whitespace; keep the question itself verbatim. The task's ordering position already conveys its letter/number.

        Top-level "sections": list ONLY sections / task groups that actually contain at least one task (e.g. "Aufgabe 1 …", "Part A", "Section 2"). Do NOT add an entry for:
        - the exam title or course name (those go to top-level "title" / "course"),
        - "Bearbeitungshinweise", "Hinweise", instructions, cover-page headings, or any other non-task heading,
        - trailing pages with extra answer space, blank pages, or scratch sheets.
        Every section name MUST be reused verbatim as the "section" field on the tasks it contains, so they can be matched. Each section may have an optional "description" containing ONLY content the student needs in order to solve the tasks in that section — shared definitions, given data, formulas, source material excerpts, references, or figure descriptions that belong to the section as a whole (not to a single task). Do NOT put general student instructions, point distributions, grading info, or processing notices into "description".

        CONTEXT PLACEMENT — read carefully. Within one section (e.g. "Aufgabe 3"), context can appear in TWO places:
        1. SECTION-INTRO context: definitions, given data, formulas, scenario setup printed BEFORE subtask a) that ALL subtasks need. Put this in the section's "description". This becomes a context block at the top of the section.
        2. MID-SECTION context: a paragraph, code listing, table, scenario reset, or new given data printed BETWEEN two subtasks that only the FOLLOWING subtasks reference. Typical wording: "Wir betrachten nun …", "Im Folgenden sei …", "Now consider …", "Sei zusätzlich …", "Gegeben sei nun …", or a fresh figure / table / code block introduced midway. For each such mid-section context, emit ONE entry in the top-level "context_blocks" array with:
           - section: the exact section name,
           - after_task_index: the 0-based index of the LAST preceding subtask in that section (so 0 means "after subtask a)", 1 means "after subtask b)", etc.),
           - content: the verbatim context text (do not shorten or paraphrase).
           Do NOT also copy that text into the section "description". Do NOT prepend it to the prompt of the following subtasks. Each task's "prompt" must be the question itself only.
           If the same context applies to ALL subtasks of the section, prefer "description" over a context_blocks entry with after_task_index: null.

        Top-level "figures": list EVERY figure, diagram, chart, schematic, plot, table-as-image, or other visual element that appears in the PDF. Do NOT attempt to reproduce or describe the image pixels — only:
        - section: the section the figure belongs to (matching the section name used on tasks).
        - after_task_index: 0-based index of the task within that section that the figure visually follows. Use null if the figure appears before the first task of the section (i.e. it is part of the section intro).
        - label: the figure's printed label if any (e.g. "Figure 2", "Abb. 3a", "Diagram 1").
        - caption: the figure's caption text verbatim, if any. Do NOT describe the image contents.
        - page_number: the page the figure appears on, if you can tell.

        Pure text formatting (bullet lists, equation lines) is NOT a figure. Source code, pseudocode, or LaTeX that is just typeset as text is also NOT a figure — keep it inline (use fenced ``` code blocks for code, `$...$` / `$$...$$` for math).
        Markdown formatting (bold, italic, lists, fenced code blocks, inline code) is allowed inside "description", "context_blocks[].content", and task "prompt" — use it only to faithfully preserve structure that already exists in the PDF.

        Top-level: title, course, detected_language ('de' | 'en' | 'other').

        If the input is plain text rather than page images, the layout is already linearized. Treat blank lines as soft section breaks, infer figures from captions/labels mentioned in the text, and ignore line-wrap artifacts.

        Return ONLY by calling extract_exam.
        """;

    private static final String SCHEMA_JSON = """
        {
          "type": "object",
          "additionalProperties": false,
          "properties": {
            "detected_language": { "type": "string", "enum": ["de", "en", "other"] },
            "title": { "type": ["string", "null"] },
            "course": { "type": ["string", "null"] },
            "sections": {
              "type": "array",
              "items": {
                "type": "object",
                "additionalProperties": false,
                "properties": {
                  "name": { "type": "string" },
                  "description": { "type": ["string", "null"] }
                },
                "required": ["name"]
              }
            },
            "figures": {
              "type": "array",
              "items": {
                "type": "object",
                "additionalProperties": false,
                "properties": {
                  "section": { "type": ["string", "null"] },
                  "after_task_index": { "type": ["integer", "null"] },
                  "label": { "type": ["string", "null"] },
                  "caption": { "type": ["string", "null"] },
                  "page_number": { "type": ["integer", "null"] }
                },
                "required": []
              }
            },
            "context_blocks": {
              "type": "array",
              "items": {
                "type": "object",
                "additionalProperties": false,
                "properties": {
                  "section": { "type": ["string", "null"] },
                  "after_task_index": { "type": ["integer", "null"] },
                  "content": { "type": "string" }
                },
                "required": ["content"]
              }
            },
            "tasks": {
              "type": "array",
              "items": {
                "type": "object",
                "additionalProperties": false,
                "properties": {
                  "section": { "type": ["string", "null"] },
                  "type": { "type": "string", "enum": ["single_choice", "multiple_choice", "text"] },
                  "prompt": { "type": "string" },
                  "options": {
                    "type": ["array", "null"],
                    "items": {
                      "type": "object",
                      "additionalProperties": false,
                      "properties": {
                        "text": { "type": "string" },
                        "is_correct": { "type": "boolean" }
                      },
                      "required": ["text", "is_correct"]
                    }
                  },
                  "points": { "type": ["number", "null"] }
                },
                "required": ["type", "prompt"]
              }
            }
          },
          "required": ["detected_language", "tasks"]
        }
        """;

    private static final Map<String, Object> SCHEMA;
    static {
        try {
            SCHEMA = new ObjectMapper().readValue(SCHEMA_JSON, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Bad extract_exam schema JSON", e);
        }
    }

    public static Map<String, Object> schema() { return SCHEMA; }
}