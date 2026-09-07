package app.ai;

/**
 * Which transport carries a model. Mirrors the `forceProvider` flag in
 * supabase/functions/_shared/ai-provider.ts.
 */
public enum ProviderKind {
    /** Native OpenAI Responses API (see {@link OpenAiResponsesProvider}). */
    OPENAI,
    /** Native Anthropic Messages API (see {@link AnthropicProvider}). */
    ANTHROPIC,
    /** Native Google Gemini generateContent API (see {@link GeminiProvider}). */
    GEMINI,
    /**
     * Withdrawn model, kept resolvable so exams and metric rows that still name
     * it keep their label — but never callable. {@link AiProviderFactory} fails
     * it with a non-transient error rather than letting a parse or solve quietly
     * answer on a different model than the one it claims.
     */
    RETIRED
}
