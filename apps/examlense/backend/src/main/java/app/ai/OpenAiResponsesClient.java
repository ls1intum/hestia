package app.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Native OpenAI Responses API shapes with forced function-tool output.
 * Transport, retry, and error mapping live in {@link ProviderHttpCaller}.
 */
final class OpenAiResponsesClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_OUTPUT_TOKENS = 16_000;

    static final ProviderHttpCaller.Adapter ADAPTER = new ProviderHttpCaller.Adapter() {
        @Override public String path(String model) { return "/responses"; }

        @Override public void applyHeaders(HttpHeaders headers, String apiKey) {
            headers.setBearerAuth(apiKey);
        }

        @Override public Map<String, Object> buildBody(String model, AiProvider.ChatRequest req) {
            return OpenAiResponsesClient.buildBody(model, req);
        }

        @Override public AiProvider.ChatResponse parseResponse(String json, String requestedModel, String providerName) {
            return OpenAiResponsesClient.parseResponse(json, requestedModel, providerName);
        }
    };

    private OpenAiResponsesClient() {}

    static Map<String, Object> buildBody(String model, AiProvider.ChatRequest req) {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("name", req.tool().name());
        tool.put("description", req.tool().description());
        tool.put("parameters", req.tool().schema());

        Map<String, Object> user = new LinkedHashMap<>();
        user.put("role", "user");
        user.put("content", toContent(req.userContent()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_output_tokens", MAX_OUTPUT_TOKENS);
        if (req.systemPrompt() != null && !req.systemPrompt().isBlank()) {
            body.put("instructions", req.systemPrompt());
        }
        body.put("input", List.of(user));
        body.put("tools", List.of(tool));
        body.put("tool_choice", Map.of("type", "function", "name", req.tool().name()));
        return body;
    }

    private static List<Map<String, Object>> toContent(AiProvider.UserContent uc) {
        List<Map<String, Object>> parts = new ArrayList<>();
        if (uc instanceof AiProvider.TextContent t) {
            parts.add(Map.of("type", "input_text", "text", t.text()));
        } else if (uc instanceof AiProvider.MultipartContent m) {
            for (AiProvider.ContentPart p : m.parts()) {
                if (p instanceof AiProvider.TextPart tp) {
                    parts.add(Map.of("type", "input_text", "text", tp.text()));
                } else if (p instanceof AiProvider.ImageUrlPart ip) {
                    parts.add(Map.of("type", "input_image", "image_url", ip.url()));
                } else if (p instanceof AiProvider.FilePart fp) {
                    Map<String, Object> file = new LinkedHashMap<>();
                    file.put("type", "input_file");
                    file.put("filename", fp.filename());
                    file.put("file_data", dataUrl(fp.mediaType(), fp.fileData()));
                    parts.add(file);
                }
            }
        } else {
            throw new IllegalStateException("Unknown user content type");
        }
        return parts;
    }

    private static String dataUrl(String mediaType, String data) {
        if (data != null && data.startsWith("data:")) return data;
        String mime = mediaType == null || mediaType.isBlank() ? "application/octet-stream" : mediaType;
        return "data:" + mime + ";base64," + (data == null ? "" : data);
    }

    @SuppressWarnings("unchecked")
    static AiProvider.ChatResponse parseResponse(String json, String requestedModel, String providerName) {
        try {
            Map<String, Object> root = MAPPER.readValue(json, new TypeReference<>() {});
            String actualModel = (String) root.getOrDefault("model", requestedModel);
            List<Map<String, Object>> output = (List<Map<String, Object>>) root.get("output");
            if (output == null || output.isEmpty()) {
                throw new AiExceptions.MalformedModelOutputException("OpenAI returned no output");
            }

            String argsStr = null;
            for (Map<String, Object> item : output) {
                if ("function_call".equals(item.get("type"))) {
                    argsStr = (String) item.get("arguments");
                    break;
                }
            }
            if (argsStr == null || argsStr.isBlank()) {
                throw new AiExceptions.MalformedModelOutputException("OpenAI did not return a tool call");
            }

            Map<String, Object> args;
            try {
                args = MAPPER.readValue(argsStr, new TypeReference<>() {});
            } catch (Exception e) {
                String preview = argsStr.substring(0, Math.min(argsStr.length(), 200));
                throw new AiExceptions.MalformedModelOutputException("OpenAI returned invalid JSON in tool args: " + preview);
            }
            return new AiProvider.ChatResponse(args, actualModel, providerName, parseUsage(root.get("usage")));
        } catch (AiExceptions.ProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new AiExceptions.MalformedModelOutputException("Failed to parse OpenAI response: " + e.getMessage());
        }
    }

    private static AiProvider.Usage parseUsage(Object usageObj) {
        if (!(usageObj instanceof Map<?, ?> usage)) return null;
        Integer input = asInt(usage.get("input_tokens"));
        Integer output = asInt(usage.get("output_tokens"));
        Integer total = asInt(usage.get("total_tokens"));
        if (total == null && input != null && output != null) total = input + output;
        return new AiProvider.Usage(input, output, total);
    }

    private static Integer asInt(Object v) {
        return v instanceof Number n ? n.intValue() : null;
    }
}
