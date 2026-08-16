package app.ai;

import java.util.List;
import java.util.Map;

/**
 * Single primitive shared by every AI-calling endpoint. Mirrors
 * supabase/functions/_shared/ai-provider.ts.
 */
public interface AiProvider {

    /** Stable identifier persisted on generated rows (e.g. "openai-compatible"). */
    String name();

    ChatResponse chat(ChatRequest req);

    // -- Request / response shapes (OpenAI-compatible) --

    /** A user message is either a plain string or a list of typed parts. */
    sealed interface UserContent permits TextContent, MultipartContent {}
    record TextContent(String text) implements UserContent {}
    record MultipartContent(List<ContentPart> parts) implements UserContent {}

    sealed interface ContentPart permits TextPart, ImageUrlPart, FilePart {}
    record TextPart(String text) implements ContentPart {}
    record ImageUrlPart(String url) implements ContentPart {}
    record FilePart(String filename, String fileData, String mediaType) implements ContentPart {}

    /** OpenAI-style function/tool definition for structured output. */
    record Tool(String name, String description, Map<String, Object> schema) {}

    record ChatRequest(String systemPrompt, UserContent userContent, Tool tool) {}

    /** Token usage reported by the provider; any field may be null if omitted. */
    record Usage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {}

    /**
     * Tool-call arguments parsed back into a Map, plus the actual model id
     * the provider used, a stable provider name, and token usage (may be null
     * for providers that don't report it).
     */
    record ChatResponse(Map<String, Object> toolArgs, String model, String providerName, Usage usage) {}
}
