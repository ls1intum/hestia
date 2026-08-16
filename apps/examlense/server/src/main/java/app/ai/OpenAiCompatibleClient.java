package app.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chat-completions shapes for any OpenAI-compatible HTTP API (OpenAI, GWDG,
 * OpenRouter, vLLM, ...). Always uses `tools` + forced `tool_choice` to get
 * structured JSON back. Transport, retry, and error mapping live in
 * {@link ProviderHttpCaller}.
 */
final class OpenAiCompatibleClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final ProviderHttpCaller.Adapter ADAPTER = new ProviderHttpCaller.Adapter() {
        @Override public String path(String model) { return "/chat/completions"; }

        @Override public void applyHeaders(HttpHeaders headers, String apiKey) {
            headers.setBearerAuth(apiKey);
        }

        @Override public Map<String, Object> buildBody(String model, AiProvider.ChatRequest req) {
            return OpenAiCompatibleClient.buildBody(model, req);
        }

        @Override public AiProvider.ChatResponse parseResponse(String json, String requestedModel, String providerName) {
            return OpenAiCompatibleClient.parseResponse(json, requestedModel, providerName);
        }
    };

    private OpenAiCompatibleClient() {}

    static Map<String, Object> buildBody(String model, AiProvider.ChatRequest req) {
        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", serializeUserContent(req.userContent()));

        List<Map<String, Object>> messages = new ArrayList<>();
        if (req.systemPrompt() != null && !req.systemPrompt().isBlank()) {
            messages.add(Map.of("role", "system", "content", req.systemPrompt()));
        }
        messages.add(userMsg);

        Map<String, Object> tool = Map.of(
            "type", "function",
            "function", Map.of(
                "name", req.tool().name(),
                "description", req.tool().description(),
                "parameters", req.tool().schema()
            )
        );

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("tools", List.of(tool));
        body.put("tool_choice", Map.of(
            "type", "function",
            "function", Map.of("name", req.tool().name())
        ));
        return body;
    }

    private static Object serializeUserContent(AiProvider.UserContent uc) {
        if (uc instanceof AiProvider.TextContent t) return t.text();
        if (uc instanceof AiProvider.MultipartContent m) {
            List<Map<String, Object>> parts = new ArrayList<>();
            for (AiProvider.ContentPart p : m.parts()) {
                if (p instanceof AiProvider.TextPart tp) {
                    parts.add(Map.of("type", "text", "text", tp.text()));
                } else if (p instanceof AiProvider.ImageUrlPart ip) {
                    parts.add(Map.of("type", "image_url", "image_url", Map.of("url", ip.url())));
                } else if (p instanceof AiProvider.FilePart fp) {
                    Map<String, Object> file = new LinkedHashMap<>();
                    file.put("filename", fp.filename());
                    file.put("file_data", fp.fileData());
                    if (fp.mediaType() != null) file.put("media_type", fp.mediaType());
                    parts.add(Map.of("type", "file", "file", file));
                }
            }
            return parts;
        }
        throw new IllegalStateException("Unknown user content type");
    }

    @SuppressWarnings("unchecked")
    static AiProvider.ChatResponse parseResponse(String json, String requestedModel, String providerName) {
        try {
            Map<String, Object> root = MAPPER.readValue(json, new TypeReference<>() {});
            String actualModel = (String) root.getOrDefault("model", requestedModel);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) root.get("choices");
            if (choices == null || choices.isEmpty()) {
                throw new AiExceptions.MalformedModelOutputException("AI provider returned no choices");
            }
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            List<Map<String, Object>> toolCalls = message == null ? null : (List<Map<String, Object>>) message.get("tool_calls");
            if (toolCalls == null || toolCalls.isEmpty()) {
                throw new AiExceptions.MalformedModelOutputException("AI provider did not return a tool call");
            }
            Map<String, Object> function = (Map<String, Object>) toolCalls.get(0).get("function");
            String argsStr = function == null ? null : (String) function.get("arguments");
            if (argsStr == null || argsStr.isBlank()) {
                throw new AiExceptions.MalformedModelOutputException("AI provider returned empty tool args");
            }
            Map<String, Object> args;
            try {
                args = MAPPER.readValue(argsStr, new TypeReference<>() {});
            } catch (Exception e) {
                String preview = argsStr.substring(0, Math.min(argsStr.length(), 200));
                throw new AiExceptions.MalformedModelOutputException("AI provider returned invalid JSON in tool args: " + preview);
            }
            return new AiProvider.ChatResponse(args, actualModel, providerName, parseUsage(root.get("usage")));
        } catch (AiExceptions.ProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new AiExceptions.MalformedModelOutputException("Failed to parse AI response: " + e.getMessage());
        }
    }

    /** Reads the OpenAI-style `usage` object; null-safe for providers that omit it. */
    private static AiProvider.Usage parseUsage(Object usageObj) {
        if (!(usageObj instanceof Map<?, ?> usage)) return null;
        return new AiProvider.Usage(
            asInt(usage.get("prompt_tokens")),
            asInt(usage.get("completion_tokens")),
            asInt(usage.get("total_tokens"))
        );
    }

    private static Integer asInt(Object v) {
        return v instanceof Number n ? n.intValue() : null;
    }
}
