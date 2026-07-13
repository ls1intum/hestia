/**
 * Client-side mirror of the server LLM strategy registries.
 *
 * The server is authoritative for behavior (provider, model id, capability
 * flags) — see `backend/src/main/java/app/ai/ParserStrategy.java` and
 * `backend/src/main/java/app/ai/SolverStrategy.java`. Unknown ids fall back to
 * the server default at request time, so drift here is low-risk.
 *
 * If you add or rename a model in either server registry, mirror the
 * id/label/description here so the dropdowns stay in sync.
 */

export type LlmModelKind = "parser" | "solver";

export interface LlmModel {
  id: string;
  label: string;
  description?: string;
}

export const PARSER_MODELS: LlmModel[] = [
  {
    id: "gemini-2.5-flash",
    label: "Gemini 2.5 Flash (Google)",
    description:
      "Google Gemini reads the PDF directly (native document parsing, no rasterization).",
  },
  {
    id: "claude-opus-4-8",
    label: "Claude Opus 4.8 (Anthropic)",
    description:
      "Anthropic flagship model; reads the PDF directly with native document input.",
  },
  {
    id: "gpt-5.5",
    label: "GPT-5.5 (OpenAI)",
    description:
      "OpenAI frontier model; reads the PDF directly with native file input.",
  },
  {
    id: "qwen3.6-35b-a3b",
    label: "Qwen 3.6 35B A3B (GWDG)",
    description: "GWDG-hosted vision model; PDF pages rasterized to images.",
  },
  {
    id: "mistral-large-3-675b-instruct-2512",
    label: "Mistral Large 3 675B (GWDG)",
    description: "GWDG-hosted vision model; PDF pages rasterized to images.",
  },
];

export const DEFAULT_PARSER_MODEL_ID = "gemini-2.5-flash";

export const LEGACY_PARSER_MODELS: LlmModel[] = [
  {
    id: "gemma-4-31b-it",
    label: "Gemma 4 31B Instruct (GWDG)",
    description: "Legacy GWDG parser model.",
  },
  {
    id: "qwen3.5-397b-a17b",
    label: "Qwen 3.5 397B A17B (GWDG)",
    description: "Legacy GWDG parser model.",
  },
  {
    id: "mistral-large-3-text",
    label: "Mistral Large 3 — Fast Mode (GWDG)",
    description: "Legacy text-only parser strategy.",
  },
  {
    id: "gemini-2.5-flash-lite",
    label: "Gemini 2.5 Flash-Lite (Google)",
    description: "Legacy Gemini parser model.",
  },
];

export const parserModelLabel = (id: string): string =>
  PARSER_MODELS.find((m) => m.id === id)?.label ??
  LEGACY_PARSER_MODELS.find((m) => m.id === id)?.label ??
  id;

export const SOLVER_MODELS: LlmModel[] = [
  {
    id: "gemini-2.5-flash",
    label: "Gemini 2.5 Flash (Google)",
    description: "Google Gemini model for fast exam solving.",
  },
  {
    id: "gpt-5.5",
    label: "GPT-5.5 (OpenAI)",
    description: "OpenAI frontier model for complex reasoning and tool-heavy work.",
  },
  {
    id: "claude-opus-4-8",
    label: "Claude Opus 4.8 (Anthropic)",
    description:
      "Anthropic flagship model for complex reasoning and long-context work.",
  },
  {
    id: "mistral-large-3-675b-instruct-2512",
    label: "Mistral Large 3 675B (GWDG)",
    description: "GWDG-hosted large reasoning model.",
  },
  {
    id: "qwen3.6-35b-a3b",
    label: "Qwen 3.6 35B A3B (GWDG)",
    description: "GWDG-hosted Qwen 3.6 mixture-of-experts model.",
  },
];

export const LEGACY_SOLVER_MODELS: LlmModel[] = [
  {
    id: "gemma-4-31b-it",
    label: "Gemma 4 31B Instruct (GWDG)",
    description: "Legacy GWDG solver model.",
  },
  {
    id: "qwen3.5-397b-a17b",
    label: "Qwen 3.5 397B A17B (GWDG)",
    description: "Legacy GWDG solver model.",
  },
];

export const DEFAULT_SOLVER_MODEL_ID = "gpt-5.5";

export const solverModelLabel = (id: string): string =>
  SOLVER_MODELS.find((m) => m.id === id)?.label ??
  LEGACY_SOLVER_MODELS.find((m) => m.id === id)?.label ??
  id;

/**
 * Models still supported by the backend but intentionally not offered in the
 * UI pickers. The backend catalog may still return them; the dialogs filter
 * them out. Kept out of the arrays above so historical exams that used them
 * still resolve a label.
 */
export const UI_HIDDEN_MODEL_IDS = new Set<string>([
  "gemma-4-31b-it",
  "qwen3.5-397b-a17b",
  "gemini-2.5-flash-lite",
]);

/** Selectable subset of a catalog for the UI pickers. */
export const selectableModels = (models: LlmModel[]): LlmModel[] =>
  models.filter((m) => !UI_HIDDEN_MODEL_IDS.has(m.id));

/** Preferred default if it survived filtering, else the first selectable id. */
export const resolveSelectableDefault = (
  models: LlmModel[],
  preferred?: string,
): string =>
  preferred && models.some((m) => m.id === preferred)
    ? preferred
    : models[0]?.id ?? "";
