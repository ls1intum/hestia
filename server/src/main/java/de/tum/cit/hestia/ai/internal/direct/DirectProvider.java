package de.tum.cit.hestia.ai.internal.direct;

import de.tum.cit.hestia.ai.LlmContent;
import java.util.Map;

/**
 * One model of a commercial provider, called directly over its native API with forced tool output.
 * Used only for models the Logos gateway does not offer.
 */
interface DirectProvider {

    /** Stable provider identifier, e.g. "openai", "anthropic". */
    String name();

    ChatResponse chat(ChatRequest req);

    record ChatRequest(String systemPrompt, LlmContent.UserContent userContent, LlmContent.Tool tool) {}

    /** Token usage reported by the provider; any field may be null if omitted. */
    record Usage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {}

    /**
     * Tool-call arguments parsed back into a Map, plus the actual model id the provider used, a
     * stable provider name, and token usage (may be null for providers that don't report it).
     */
    record ChatResponse(Map<String, Object> toolArgs, String model, String providerName, Usage usage) {}
}
