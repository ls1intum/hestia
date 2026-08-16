package app.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Native Anthropic Messages API shapes with forced tool output. Transport,
 * retry, and error mapping live in {@link ProviderHttpCaller}.
 */
final class AnthropicClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int MAX_TOKENS = 16_000;

    static final ProviderHttpCaller.Adapter ADAPTER = new ProviderHttpCaller.Adapter() {
        @Override public String path(String model) { return "/v1/messages"; }

        @Override public void applyHeaders(HttpHeaders headers, String apiKey) {
            headers.set("x-api-key", apiKey);
            headers.set("anthropic-version", ANTHROPIC_VERSION);
        }

        @Override public Map<String, Object> buildBody(String model, AiProvider.ChatRequest req) {
            return AnthropicClient.buildBody(model, req);
        }

        @Override public AiProvider.ChatResponse parseResponse(String json, String requestedModel, String providerName) {
            return AnthropicClient.parseResponse(json, requestedModel, providerName);
        }
    };

    private AnthropicClient() {}

    static Map<String, Object> buildBody(String model, AiProvider.ChatRequest req) {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", req.tool().name());
        tool.put("description", req.tool().description());
        tool.put("input_schema", req.tool().schema());

        Map<String, Object> user = new LinkedHashMap<>();
        user.put("role", "user");
        user.put("content", toContent(req.userContent()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", MAX_TOKENS);
        if (req.systemPrompt() != null && !req.systemPrompt().isBlank()) {
            body.put("system", req.systemPrompt());
        }
        body.put("messages", List.of(user));
        body.put("tools", List.of(tool));
        body.put("tool_choice", Map.of("type", "tool", "name", req.tool().name()));
        return body;
    }

    private static List<Map<String, Object>> toContent(AiProvider.UserContent uc) {
        List<Map<String, Object>> parts = new ArrayList<>();
        if (uc instanceof AiProvider.TextContent t) {
            parts.add(Map.of("type", "text", "text", t.text()));
        } else if (uc instanceof AiProvider.MultipartContent m) {
            for (AiProvider.ContentPart p : m.parts()) {
                if (p instanceof AiProvider.TextPart tp) {
                    parts.add(Map.of("type", "text", "text", tp.text()));
                } else if (p instanceof AiProvider.ImageUrlPart ip) {
                    DataUrl data = DataUrl.parse(ip.url(), "image/png");
                    parts.add(Map.of(
                        "type", "image",
                        "source", Map.of(
                            "type", "base64",
                            "media_type", data.mediaType(),
                            "data", data.data()
                        )
                    ));
                } else if (p instanceof AiProvider.FilePart fp) {
                    parts.add(Map.of(
                        "type", "document",
                        "source", Map.of(
                            "type", "base64",
                            "media_type", fp.mediaType() == null ? "application/octet-stream" : fp.mediaType(),
                            "data", DataUrl.parse(fp.fileData(), fp.mediaType()).data()
                        )
                    ));
                }
            }
        } else {
            throw new IllegalStateException("Unknown user content type");
        }
        return parts;
    }

    @SuppressWarnings("unchecked")
    static AiProvider.ChatResponse parseResponse(String json, String requestedModel, String providerName) {
        try {
            Map<String, Object> root = MAPPER.readValue(json, new TypeReference<>() {});
            String actualModel = (String) root.getOrDefault("model", requestedModel);
            List<Map<String, Object>> content = (List<Map<String, Object>>) root.get("content");
            if (content == null || content.isEmpty()) {
                throw new AiExceptions.MalformedModelOutputException("Anthropic returned no content");
            }

            Map<String, Object> args = null;
            for (Map<String, Object> item : content) {
                if ("tool_use".equals(item.get("type"))) {
                    args = (Map<String, Object>) item.get("input");
                    break;
                }
            }
            if (args == null) {
                throw new AiExceptions.MalformedModelOutputException("Anthropic did not return a tool call");
            }
            return new AiProvider.ChatResponse(args, actualModel, providerName, parseUsage(root.get("usage")));
        } catch (AiExceptions.ProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new AiExceptions.MalformedModelOutputException("Failed to parse Anthropic response: " + e.getMessage());
        }
    }

    private static AiProvider.Usage parseUsage(Object usageObj) {
        if (!(usageObj instanceof Map<?, ?> usage)) return null;
        Integer input = asInt(usage.get("input_tokens"));
        Integer output = asInt(usage.get("output_tokens"));
        Integer total = input != null && output != null ? input + output : null;
        return new AiProvider.Usage(input, output, total);
    }

    private static Integer asInt(Object v) {
        return v instanceof Number n ? n.intValue() : null;
    }
}
