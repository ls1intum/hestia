package app.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Builds AiProvider instances from a strategy + the configured env.
 *
 * Mirrors getAIProvider() in supabase/functions/_shared/ai-provider.ts with
 * the same forceProvider semantics: each strategy pins a transport, and the
 * provider model id is taken verbatim from the strategy.
 */
@Component
public class AiProviderFactory {

    @Value("${ai.openai.api-key:}")
    private String openaiApiKey;

    @Value("${ai.openai.base-url:https://api.openai.com/v1}")
    private String openaiBaseUrl;

    @Value("${ai.anthropic.api-key:}")
    private String anthropicApiKey;

    @Value("${ai.anthropic.base-url:https://api.anthropic.com}")
    private String anthropicBaseUrl;

    @Value("${ai.gemini.api-key:}")
    private String geminiApiKey;

    @Value("${ai.gemini.base-url:https://generativelanguage.googleapis.com/v1beta}")
    private String geminiBaseUrl;

    /**
     * Providers are immutable and hold a pooled HTTP client, so build each
     * (kind, model) combination once and reuse it across requests.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, AiProvider> cache =
        new java.util.concurrent.ConcurrentHashMap<>();

    public AiProvider forSolver(SolverStrategy strategy) {
        return build(strategy.providerKind(), strategy.providerModel());
    }

    public AiProvider forParser(ParserStrategy strategy) {
        return build(strategy.providerKind(), strategy.providerModel());
    }

    public AiProvider build(ProviderKind kind, String model) {
        return cache.computeIfAbsent(kind + ":" + model, k -> create(kind, model));
    }

    private AiProvider create(ProviderKind kind, String model) {
        return switch (kind) {
            case OPENAI -> new OpenAiResponsesProvider(
                require(openaiApiKey, "openai provider requires OPENAI_API_KEY"), openaiBaseUrl, model);
            case ANTHROPIC -> new AnthropicProvider(
                require(anthropicApiKey, "anthropic provider requires ANTHROPIC_API_KEY"), anthropicBaseUrl, model);
            case GEMINI -> new GeminiProvider(
                require(geminiApiKey, "gemini provider requires GEMINI_API_KEY"), geminiBaseUrl, model);
            // 410, not 500: AiExceptions.isTransient treats 5xx as retryable, and a
            // retired model must never be retried into a fallback that answers as a
            // different model than the exam records.
            case RETIRED -> throw new AiExceptions.ProviderException(RETIRED_MESSAGE, 410);
        };
    }

    /** Thrown when something still references a withdrawn model. */
    public static final String RETIRED_MESSAGE =
        "This model has been retired and is no longer available.";

    private static String require(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new AiExceptions.ProviderException(message, 500);
        }
        return value;
    }
}
