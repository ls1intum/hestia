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
    logoSrc:
      "https://commons.wikimedia.org/wiki/Special:Redirect/file/Google_Gemini_icon_2025.svg",
  },
  "gemini-2.5-flash": {
    provider: "Google",
    name: "Gemini Flash",
    logoSrc:
      "https://commons.wikimedia.org/wiki/Special:Redirect/file/Google_Gemini_icon_2025.svg",
  },
  "gpt-5.5": {
    provider: "OpenAI",
    name: "GPT",
    logoSrc:
      "https://commons.wikimedia.org/wiki/Special:Redirect/file/OpenAI_logo_2025_%28symbol%29.svg",
  },
  "claude-opus-4-8": {
    provider: "Anthropic",
    name: "Claude",
    logoSrc:
      "https://commons.wikimedia.org/wiki/Special:Redirect/file/Claude_AI_symbol.svg",
  },
  "mistral-large-3-675b-instruct-2512": {
    provider: "GWDG",
    name: "Mistral Large",
    logoSrc:
      "https://commons.wikimedia.org/wiki/Special:Redirect/file/Mistral_AI_logo_%282025%E2%80%93%29.svg",
  },
  "qwen3.6-35b-a3b": {
    provider: "GWDG",
    name: "Qwen 3.6",
    logoSrc:
      "https://commons.wikimedia.org/wiki/Special:Redirect/file/Qwen_logo.svg",
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
