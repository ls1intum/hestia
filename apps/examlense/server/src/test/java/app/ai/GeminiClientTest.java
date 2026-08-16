package app.ai;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Gemini speaks its own request/response dialect, so the request builder and the
 * JSON-Schema→OpenAPI-subset translation are the most error-prone (and entirely
 * offline-testable) parts. Mirrors {@link ProviderRequestShapeTest}'s style.
 */
class GeminiClientTest {

    private static final AiProvider.Tool TOOL = new AiProvider.Tool(
        "submit_answers",
        "Submit answers.",
        Map.of("type", "object", "properties", Map.of("answers", Map.of("type", "array")))
    );

    @Test
    @SuppressWarnings("unchecked")
    void buildBodyForcesTheToolCallViaModeAny() {
        Map<String, Object> body = GeminiClient.buildBody(
            new AiProvider.ChatRequest("system", new AiProvider.TextContent("solve this"), TOOL));

        assertThat(((Map<String, Object>) body.get("system_instruction"))).isNotNull();

        Map<String, Object> toolConfig = (Map<String, Object>) body.get("tool_config");
        Map<String, Object> fcc = (Map<String, Object>) toolConfig.get("function_calling_config");
        assertThat(fcc).containsEntry("mode", "ANY");
        assertThat((List<String>) fcc.get("allowed_function_names")).containsExactly("submit_answers");
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildBodyEncodesPdfAsInlineData() {
        Map<String, Object> body = GeminiClient.buildBody(new AiProvider.ChatRequest(
            "system",
            new AiProvider.MultipartContent(List.of(
                new AiProvider.TextPart("Extract the exam."),
                new AiProvider.FilePart("exam.pdf", "JVBERi0x", "application/pdf")
            )),
            TOOL));

        List<Map<String, Object>> contents = (List<Map<String, Object>>) body.get("contents");
        List<Map<String, Object>> parts = (List<Map<String, Object>>) contents.get(0).get("parts");
        Map<String, Object> inlineData = (Map<String, Object>) parts.get(1).get("inline_data");
        assertThat(inlineData).containsEntry("mime_type", "application/pdf").containsEntry("data", "JVBERi0x");
    }

    @Test
    @SuppressWarnings("unchecked")
    void toGeminiSchemaCollapsesNullableUnionType() {
        Object out = GeminiClient.toGeminiSchema(Map.of(
            "type", List.of("string", "null"),
            "description", "maybe a string"));

        Map<String, Object> m = (Map<String, Object>) out;
        assertThat(m).containsEntry("type", "string").containsEntry("nullable", true);
        assertThat(m).containsEntry("description", "maybe a string");
    }

    @Test
    @SuppressWarnings("unchecked")
    void toGeminiSchemaDropsKeywordsGeminiRejects() {
        Object out = GeminiClient.toGeminiSchema(Map.of(
            "type", "object",
            "additionalProperties", false,
            "$schema", "http://json-schema.org/draft/2020-12/schema",
            "properties", Map.of("name", Map.of("type", "string"))));

        Map<String, Object> m = (Map<String, Object>) out;
        assertThat(m).doesNotContainKeys("additionalProperties", "$schema");
        assertThat((Map<String, Object>) m.get("properties")).containsKey("name");
    }

    @Test
    @SuppressWarnings("unchecked")
    void toGeminiSchemaRecursesIntoArrayItems() {
        Object out = GeminiClient.toGeminiSchema(Map.of(
            "type", "array",
            "items", Map.of("type", List.of("integer", "null"))));

        Map<String, Object> m = (Map<String, Object>) out;
        Map<String, Object> items = (Map<String, Object>) m.get("items");
        assertThat(items).containsEntry("type", "integer").containsEntry("nullable", true);
    }

    @Test
    void parseResponseExtractsFunctionCallArgs() {
        String json = """
            {"modelVersion":"gemini-2.5-flash",
             "candidates":[{"content":{"parts":[{"functionCall":{"name":"submit_answers","args":{"answers":[]}}}]}}],
             "usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":5,"totalTokenCount":15}}
            """;

        AiProvider.ChatResponse res = GeminiClient.parseResponse(json, "gemini-2.5-flash", "gemini");

        assertThat(res.model()).isEqualTo("gemini-2.5-flash");
        assertThat(res.toolArgs()).containsKey("answers");
        assertThat(res.usage().totalTokens()).isEqualTo(15);
    }

    @Test
    void parseResponseWithNoCandidatesFailsClearly() {
        String json = "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}";

        assertThatThrownBy(() -> GeminiClient.parseResponse(json, "gemini-2.5-flash", "gemini"))
            .isInstanceOf(AiExceptions.ProviderException.class)
            .hasMessageContaining("no candidates");
    }

    @Test
    void parseResponseWithNoToolCallFailsWithTheStructuredDataPhrase() {
        String json = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hi\"}]}}]}";

        assertThatThrownBy(() -> GeminiClient.parseResponse(json, "gemini-2.5-flash", "gemini"))
            .isInstanceOf(AiExceptions.ProviderException.class)
            .hasMessageContaining("did not return a tool call");
    }
}
