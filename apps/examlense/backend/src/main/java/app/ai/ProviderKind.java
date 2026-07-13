package app.ai;

/**
 * Which transport carries a model. Mirrors the `forceProvider` flag in
 * supabase/functions/_shared/ai-provider.ts.
 */
public enum ProviderKind {
    OPENAI_COMPATIBLE,
    /** Native OpenAI Responses API (see {@link OpenAiResponsesProvider}). */
    OPENAI,
    /** Native Anthropic Messages API (see {@link AnthropicProvider}). */
    ANTHROPIC,
    /** Native Google Gemini generateContent API (see {@link GeminiProvider}). */
    GEMINI
}
