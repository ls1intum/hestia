import claudeLogo from "@/assets/model-logos/claude.svg";
import geminiLogo from "@/assets/model-logos/gemini.svg";
import mistralLogo from "@/assets/model-logos/mistral.svg";
import openaiLogo from "@/assets/model-logos/openai.svg";
import qwenLogo from "@/assets/model-logos/qwen.svg";

/**
 * Presentation metadata (provider label + logo) for LLM models, shared by the
 * create-exam parser/solver pickers and the results overview. The behavioral
 * catalog lives in `llm-models.ts`; this is display-only.
 */

export interface ModelMeta {
  provider: string;
  name: string;
  logoSrc: string;
}

export const MODEL_META: Record<string, ModelMeta> = {
  "gemini-3.5-flash": {
    provider: "Google",
    name: "Gemini 3.5 Flash",
    logoSrc: geminiLogo,
  },
  "gemini-2.5-flash": {
    provider: "Google",
    name: "Gemini Flash",
    logoSrc: geminiLogo,
  },
  "gpt-5.5": {
    provider: "OpenAI",
    name: "GPT 5.5",
    logoSrc: openaiLogo,
  },
  "claude-opus-4-8": {
    provider: "Anthropic",
    name: "Claude Opus 4.8",
    logoSrc: claudeLogo,
  },
  "mistral-large-3-675b-instruct-2512": {
    provider: "GWDG",
    name: "Mistral Large",
    logoSrc: mistralLogo,
  },
  "qwen3.6-35b-a3b": {
    provider: "GWDG",
    name: "Qwen 3.6",
    logoSrc: qwenLogo,
  },
};

export const MODEL_ORDER = [
  "gemini-3.5-flash",
  "gemini-2.5-flash",
  "gpt-5.5",
  "claude-opus-4-8",
  "mistral-large-3-675b-instruct-2512",
  "qwen3.6-35b-a3b",
];

export const modelMeta = (id: string): ModelMeta | undefined => MODEL_META[id];
