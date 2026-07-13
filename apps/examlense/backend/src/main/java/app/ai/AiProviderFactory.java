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

    @Value("${ai.openai-compatible.api-key:}")
    private String openaiCompatibleApiKey;

    @Value("${ai.openai-compatible.base-url:}")
    private String openaiCompatibleBaseUrl;

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
            case OPENAI_COMPATIBLE -> new OpenAiCompatibleProvider(
                require(openaiCompatibleApiKey, "openai-compatible provider requires AI_API_KEY and AI_BASE_URL"),
                require(openaiCompatibleBaseUrl, "openai-compatible provider requires AI_API_KEY and AI_BASE_URL"),
                model);
            case OPENAI -> new OpenAiResponsesProvider(
                require(openaiApiKey, "openai provider requires OPENAI_API_KEY"), openaiBaseUrl, model);
            case ANTHROPIC -> new AnthropicProvider(
                require(anthropicApiKey, "anthropic provider requires ANTHROPIC_API_KEY"), anthropicBaseUrl, model);
            case GEMINI -> new GeminiProvider(
                require(geminiApiKey, "gemini provider requires GEMINI_API_KEY"), geminiBaseUrl, model);
        };
    }

    private static String require(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new AiExceptions.ProviderException(message, 500);
        }
        return value;
    }
}
