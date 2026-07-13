package app.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Native Google Gemini (generateContent) shapes.
 *
 * Unlike {@link OpenAiCompatibleClient} this speaks Gemini's own request /
 * response dialect, so we can send the raw PDF as an {@code inline_data} document
 * part (the PDF_DIRECT strategy) and let Gemini parse the document natively —
 * layout, tables, figures, scanned pages — instead of rasterizing to images.
 *
 * Structured output: a single {@code functionDeclaration} plus
 * {@code tool_config.mode = ANY} forces Gemini to answer by calling the tool,
 * mirroring the OpenAI forced {@code tool_choice}. The returned {@code args} is
 * already a parsed object (no JSON-in-a-string step).
 *
 * Transport, retry, and error mapping live in {@link ProviderHttpCaller}.
 */
final class GeminiClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final ProviderHttpCaller.Adapter ADAPTER = new ProviderHttpCaller.Adapter() {
        @Override public String path(String model) { return "/models/" + model + ":generateContent"; }

        @Override public void applyHeaders(HttpHeaders headers, String apiKey) {
            headers.set("x-goog-api-key", apiKey);
        }

        @Override public Map<String, Object> buildBody(String model, AiProvider.ChatRequest req) {
            return GeminiClient.buildBody(req);
        }

        @Override public AiProvider.ChatResponse parseResponse(String json, String requestedModel, String providerName) {
            return GeminiClient.parseResponse(json, requestedModel, providerName);
        }
    };

    private GeminiClient() {}

    // -- Request building --

    static Map<String, Object> buildBody(AiProvider.ChatRequest req) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (req.systemPrompt() != null && !req.systemPrompt().isBlank()) {
            body.put("system_instruction", Map.of("parts", List.of(Map.of("text", req.systemPrompt()))));
        }

        Map<String, Object> userTurn = new LinkedHashMap<>();
        userTurn.put("role", "user");
        userTurn.put("parts", toParts(req.userContent()));
        body.put("contents", List.of(userTurn));

        if (req.tool() != null) {
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", req.tool().name());
            fn.put("description", req.tool().description());
            fn.put("parameters", toGeminiSchema(req.tool().schema()));
            body.put("tools", List.of(Map.of("function_declarations", List.of(fn))));
            // mode=ANY + a single allowed name forces the tool call (structured output).
            body.put("tool_config", Map.of("function_calling_config", Map.of(
                "mode", "ANY",
                "allowed_function_names", List.of(req.tool().name())
            )));
        }
        return body;
    }

    private static List<Map<String, Object>> toParts(AiProvider.UserContent uc) {
        List<Map<String, Object>> parts = new ArrayList<>();
        if (uc instanceof AiProvider.TextContent t) {
            parts.add(Map.of("text", t.text()));
        } else if (uc instanceof AiProvider.MultipartContent m) {
            for (AiProvider.ContentPart p : m.parts()) {
                if (p instanceof AiProvider.TextPart tp) {
                    parts.add(Map.of("text", tp.text()));
                } else if (p instanceof AiProvider.ImageUrlPart ip) {
                    // ParseExamService encodes rasterized page images as data URLs.
                    parts.add(inlineFromDataUrl(ip.url()));
                } else if (p instanceof AiProvider.FilePart fp) {
                    parts.add(Map.of("inline_data", Map.of(
                        "mime_type", fp.mediaType() == null ? "application/octet-stream" : fp.mediaType(),
                        "data", fp.fileData()
                    )));
                }
            }
        } else {
            throw new IllegalStateException("Unknown user content type");
        }
        return parts;
    }

    /** Convert a {@code data:<mime>;base64,<data>} URL into a Gemini inline_data part. */
    private static Map<String, Object> inlineFromDataUrl(String url) {
        if (url != null && url.startsWith("data:")) {
            DataUrl data = DataUrl.parse(url, "application/octet-stream");
            return Map.of("inline_data", Map.of("mime_type", data.mediaType(), "data", data.data()));
        }
        // Remote URL fallback (not used by the current pipeline).
        return Map.of("file_data", Map.of("file_uri", url == null ? "" : url));
    }

    /**
     * Translate our JSON-Schema tool schema into Gemini's OpenAPI-subset dialect:
     * union {@code type: ["x","null"]} → {@code type: "x", nullable: true}, and
     * drop keywords Gemini rejects ({@code additionalProperties}, {@code $schema}).
     * Only whitelisted keywords are copied through.
     */
    @SuppressWarnings("unchecked")
    static Object toGeminiSchema(Object node) {
        if (!(node instanceof Map<?, ?> raw)) return node;
        Map<String, Object> in = (Map<String, Object>) raw;
        Map<String, Object> out = new LinkedHashMap<>();

        Object type = in.get("type");
        if (type instanceof List<?> types) {
            boolean nullable = false;
            String primary = null;
            for (Object tt : types) {
                if ("null".equals(tt)) nullable = true;
                else if (primary == null) primary = String.valueOf(tt);
            }
            if (primary != null) out.put("type", primary);
            if (nullable) out.put("nullable", true);
        } else if (type != null) {
            out.put("type", type);
        }

        copyIfPresent(in, out, "description");
        copyIfPresent(in, out, "enum");
        copyIfPresent(in, out, "format");
        copyIfPresent(in, out, "required");

        if (in.get("items") != null) {
            out.put("items", toGeminiSchema(in.get("items")));
        }
        if (in.get("properties") instanceof Map<?, ?> props) {
            Map<String, Object> outProps = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : props.entrySet()) {
                outProps.put(String.valueOf(e.getKey()), toGeminiSchema(e.getValue()));
            }
            out.put("properties", outProps);
        }
        // additionalProperties / $schema intentionally omitted — Gemini rejects them.
        return out;
    }

    private static void copyIfPresent(Map<String, Object> in, Map<String, Object> out, String key) {
        if (in.containsKey(key)) out.put(key, in.get(key));
    }

    // -- Response parsing --

    @SuppressWarnings("unchecked")
    static AiProvider.ChatResponse parseResponse(String json, String requestedModel, String providerName) {
        try {
            Map<String, Object> root = MAPPER.readValue(json, new TypeReference<>() {});
            String actualModel = (String) root.getOrDefault("modelVersion", requestedModel);

            List<Map<String, Object>> candidates = (List<Map<String, Object>>) root.get("candidates");
            if (candidates == null || candidates.isEmpty()) {
                Object pf = root.get("promptFeedback");
                throw new AiExceptions.MalformedModelOutputException(
                    "Gemini returned no candidates" + (pf != null ? " (" + pf + ")" : ""));
            }

            Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
            List<Map<String, Object>> parts = content == null ? null : (List<Map<String, Object>>) content.get("parts");
            Map<String, Object> args = null;
            if (parts != null) {
                for (Map<String, Object> part : parts) {
                    Object fc = part.get("functionCall");
                    if (fc instanceof Map<?, ?> fcMap) {
                        args = (Map<String, Object>) fcMap.get("args");
                        break;
                    }
                }
            }
            if (args == null) {
                throw new AiExceptions.MalformedModelOutputException("Gemini did not return a tool call");
            }
            return new AiProvider.ChatResponse(args, actualModel, providerName, parseUsage(root.get("usageMetadata")));
        } catch (AiExceptions.ProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new AiExceptions.MalformedModelOutputException("Failed to parse Gemini response: " + e.getMessage());
        }
    }

    /** Reads Gemini's {@code usageMetadata}; null-safe. */
    private static AiProvider.Usage parseUsage(Object usageObj) {
        if (!(usageObj instanceof Map<?, ?> usage)) return null;
        return new AiProvider.Usage(
            asInt(usage.get("promptTokenCount")),
            asInt(usage.get("candidatesTokenCount")),
            asInt(usage.get("totalTokenCount"))
        );
    }

    private static Integer asInt(Object v) {
        return v instanceof Number n ? n.intValue() : null;
    }
}
