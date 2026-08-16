package app.ai;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The OpenAI-compatible client is the workhorse for the GWDG models. Its request
 * shape (forced tool_choice) and response parsing (tool_call args are a JSON
 * string that must be re-parsed) are offline-testable and were previously
 * uncovered. Style mirrors {@link ProviderRequestShapeTest}.
 */
class OpenAiCompatibleClientTest {

    private static final AiProvider.Tool TOOL = new AiProvider.Tool(
        "submit_answers",
        "Submit answers.",
        Map.of("type", "object", "properties", Map.of("answers", Map.of("type", "array")))
    );

    @Test
    @SuppressWarnings("unchecked")
    void buildBodyUsesForcedToolChoiceAndSystemPlusUserMessages() {
        Map<String, Object> body = OpenAiCompatibleClient.buildBody(
            "qwen3.6-35b-a3b",
            new AiProvider.ChatRequest("system", new AiProvider.TextContent("solve this"), TOOL));

        assertThat(body.get("model")).isEqualTo("qwen3.6-35b-a3b");
        assertThat(body.get("tool_choice")).isEqualTo(Map.of(
            "type", "function", "function", Map.of("name", "submit_answers")));

        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");
        assertThat(messages.get(0)).containsEntry("role", "system").containsEntry("content", "system");
        assertThat(messages.get(1)).containsEntry("role", "user").containsEntry("content", "solve this");
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildBodySerializesMultipartImagesAsImageUrlParts() {
        Map<String, Object> body = OpenAiCompatibleClient.buildBody(
            "qwen3.6-35b-a3b",
            new AiProvider.ChatRequest(
                "system",
                new AiProvider.MultipartContent(List.of(
                    new AiProvider.TextPart("Extract the exam."),
                    new AiProvider.ImageUrlPart("data:image/png;base64,AAAA")
                )),
                TOOL));

        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");
        List<Map<String, Object>> content = (List<Map<String, Object>>) messages.get(1).get("content");
        assertThat(content.get(0)).containsEntry("type", "text");
        Map<String, Object> image = content.get(1);
        assertThat(image).containsEntry("type", "image_url");
        assertThat((Map<String, Object>) image.get("image_url")).containsEntry("url", "data:image/png;base64,AAAA");
    }

    @Test
    void parseResponseReParsesToolCallArgumentJson() {
        String json = """
            {"model":"qwen3.6-35b-a3b",
             "choices":[{"message":{"tool_calls":[{"function":{"name":"submit_answers","arguments":"{\\"answers\\":[]}"}}]}}],
             "usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10}}
            """;

        AiProvider.ChatResponse res = OpenAiCompatibleClient.parseResponse(json, "qwen3.6-35b-a3b", "openai-compatible");

        assertThat(res.toolArgs()).containsKey("answers");
        assertThat(res.usage().totalTokens()).isEqualTo(10);
    }

    @Test
    void parseResponseWithNoToolCallFails() {
        String json = "{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}";

        assertThatThrownBy(() -> OpenAiCompatibleClient.parseResponse(json, "m", "openai-compatible"))
            .isInstanceOf(AiExceptions.ProviderException.class)
            .hasMessageContaining("did not return a tool call");
    }

    @Test
    void parseResponseWithMalformedArgsJsonFails() {
        String json = """
            {"choices":[{"message":{"tool_calls":[{"function":{"name":"submit_answers","arguments":"{not json"}}]}}]}
            """;

        assertThatThrownBy(() -> OpenAiCompatibleClient.parseResponse(json, "m", "openai-compatible"))
            .isInstanceOf(AiExceptions.ProviderException.class)
            .hasMessageContaining("invalid JSON");
    }

    @Test
    void parseResponseIsNullSafeWhenUsageOmitted() {
        String json = """
            {"choices":[{"message":{"tool_calls":[{"function":{"name":"submit_answers","arguments":"{}"}}]}}]}
            """;

        AiProvider.ChatResponse res = OpenAiCompatibleClient.parseResponse(json, "m", "openai-compatible");

        assertThat(res.usage()).isNull();
    }
}
