package de.tum.cit.hestia.ai;

import java.util.Map;

/**
 * A model's answer: the text (Logos) or the parsed tool arguments (direct providers, which always
 * answer through the forced tool), the model id the provider reports and the token usage.
 */
public record LlmResponse(String text, Map<String, Object> toolArgs, String model, Provider provider, Usage usage) {

    public LlmResponse {
        usage = usage == null ? Usage.NONE : usage;
    }

    /** Token counts as reported; any may be null. Reasoning tokens are part of the completion tokens. */
    public record Usage(Integer promptTokens, Integer completionTokens, Integer reasoningTokens) {

        public static final Usage NONE = new Usage(null, null, null);
    }
}
