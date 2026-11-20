package de.tum.cit.hestia.ai.internal.direct;

import de.tum.cit.hestia.ai.AiExceptions;
import de.tum.cit.hestia.ai.LlmRequest;
import de.tum.cit.hestia.ai.LlmResponse;
import de.tum.cit.hestia.ai.Provider;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Calls the commercial providers directly, for models the Logos gateway does not offer. Keys and
 * base URLs come from the environment ({@code hestia.ai.<provider>.*}). Reached only through the
 * gateway, which checks consent first.
 */
@Component
public class DirectProviders {

    @Value("${hestia.ai.openai.api-key:}")
    private String openaiApiKey;

    @Value("${hestia.ai.openai.base-url:}")
    private String openaiBaseUrl;

    @Value("${hestia.ai.anthropic.api-key:}")
    private String anthropicApiKey;

    @Value("${hestia.ai.anthropic.base-url:}")
    private String anthropicBaseUrl;

    @Value("${hestia.ai.gemini.api-key:}")
    private String geminiApiKey;

    @Value("${hestia.ai.gemini.base-url:}")
    private String geminiBaseUrl;

    /**
     * Providers are immutable and hold a pooled HTTP client, so build each (provider, model)
     * combination once and reuse it across requests.
     */
    private final ConcurrentHashMap<String, DirectProvider> cache = new ConcurrentHashMap<>();

    /** Whether a key for {@code provider} is configured. */
    public boolean isConfigured(Provider provider) {
        return switch (provider) {
            case OPENAI -> present(openaiApiKey);
            case ANTHROPIC -> present(anthropicApiKey);
            case GEMINI -> present(geminiApiKey);
            case LOGOS -> false;
        };
    }

    /** Sends {@code request} to its provider; the request must carry a tool (forced structured output). */
    public LlmResponse call(LlmRequest request) {
        if (request.tool() == null) {
            throw new IllegalArgumentException("Direct providers answer through a tool; the request has none");
        }
        Provider provider = request.model().provider();
        DirectProvider client = build(provider, request.model().id());
        DirectProvider.ChatResponse res = client.chat(
                new DirectProvider.ChatRequest(request.system(), request.user(), request.tool()));
        DirectProvider.Usage usage = res.usage();
        return new LlmResponse(null, res.toolArgs(), res.model(), provider,
                usage == null ? null : new LlmResponse.Usage(usage.promptTokens(), usage.completionTokens(), null));
    }

    DirectProvider build(Provider provider, String model) {
        return cache.computeIfAbsent(provider + ":" + model, k -> create(provider, model));
    }

    private DirectProvider create(Provider provider, String model) {
        return switch (provider) {
            case OPENAI -> new OpenAiResponsesProvider(
                require(openaiApiKey, "openai provider requires OPENAI_API_KEY"), openaiBaseUrl, model);
            case ANTHROPIC -> new AnthropicProvider(
                require(anthropicApiKey, "anthropic provider requires ANTHROPIC_API_KEY"), anthropicBaseUrl, model);
            case GEMINI -> new GeminiProvider(
                require(geminiApiKey, "gemini provider requires GEMINI_API_KEY"), geminiBaseUrl, model);
            case LOGOS -> throw new IllegalArgumentException("Logos is not a direct provider");
        };
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String require(String value, String message) {
        if (!present(value)) {
            throw new AiExceptions.ProviderException(message, 500);
        }
        return value;
    }
}
