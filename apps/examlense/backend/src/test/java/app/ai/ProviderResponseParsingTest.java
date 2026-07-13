package app.ai;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Response-envelope parsing per provider, with canned JSON. These paths absorb
 * nondeterministic model output — the exact failure modes that must surface as
 * typed {@link AiExceptions.MalformedModelOutputException}s, never as raw NPEs.
 */
class ProviderResponseParsingTest {

    // -- OpenAI-compatible (chat/completions) --

    @Test
    void openAiCompatibleParsesToolCallAndUsage() {
        String json = """
            {"model":"qwen3.6-35b-a3b","choices":[{"message":{"tool_calls":[
              {"function":{"name":"submit_answers","arguments":"{\\"answers\\":[{\\"task_id\\":\\"t1\\"}]}"}}]}}],
             "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}
            """;
        AiProvider.ChatResponse res = OpenAiCompatibleClient.parseResponse(json, "requested", "openai-compatible");
        assertThat(res.model()).isEqualTo("qwen3.6-35b-a3b");
        assertThat(res.toolArgs()).containsKey("answers");
        assertThat(res.usage().totalTokens()).isEqualTo(15);
    }

    @Test
    void openAiCompatibleRejectsMissingToolCall() {
        String json = """
            {"choices":[{"message":{"content":"I refuse to call tools"}}]}
            """;
        assertThatThrownBy(() -> OpenAiCompatibleClient.parseResponse(json, "m", "p"))
            .isInstanceOf(AiExceptions.MalformedModelOutputException.class);
    }

    @Test
    void openAiCompatibleRejectsInvalidJsonArguments() {
        String json = """
            {"choices":[{"message":{"tool_calls":[{"function":{"arguments":"{not json"}}]}}]}
            """;
        assertThatThrownBy(() -> OpenAiCompatibleClient.parseResponse(json, "m", "p"))
            .isInstanceOf(AiExceptions.MalformedModelOutputException.class);
    }

    @Test
    void openAiCompatibleRejectsUnparseableEnvelope() {
        assertThatThrownBy(() -> OpenAiCompatibleClient.parseResponse("not json at all", "m", "p"))
            .isInstanceOf(AiExceptions.MalformedModelOutputException.class);
    }

    // -- OpenAI Responses --

    @Test
    void openAiResponsesParsesFunctionCall() {
        String json = """
            {"model":"gpt-5.5","output":[
               {"type":"reasoning"},
               {"type":"function_call","arguments":"{\\"answers\\":[]}"}],
             "usage":{"input_tokens":7,"output_tokens":3,"total_tokens":10}}
            """;
        AiProvider.ChatResponse res = OpenAiResponsesClient.parseResponse(json, "requested", "openai");
        assertThat(res.toolArgs()).containsEntry("answers", List.of());
        assertThat(res.usage().promptTokens()).isEqualTo(7);
    }

    @Test
    void openAiResponsesRejectsOutputWithoutFunctionCall() {
        String json = """
            {"output":[{"type":"message","content":[{"type":"output_text","text":"hi"}]}]}
            """;
        assertThatThrownBy(() -> OpenAiResponsesClient.parseResponse(json, "m", "p"))
            .isInstanceOf(AiExceptions.MalformedModelOutputException.class);
    }

    // -- Anthropic --

    @Test
    void anthropicParsesToolUseBlock() {
        String json = """
            {"model":"claude-opus-4-8","content":[
               {"type":"text","text":"thinking..."},
               {"type":"tool_use","name":"submit_answers","input":{"answers":[]}}],
             "usage":{"input_tokens":4,"output_tokens":6}}
            """;
        AiProvider.ChatResponse res = AnthropicClient.parseResponse(json, "requested", "anthropic");
        assertThat(res.toolArgs()).containsKey("answers");
        // Anthropic reports no total — derived from input + output.
        assertThat(res.usage().totalTokens()).isEqualTo(10);
    }

    @Test
    void anthropicRejectsContentWithoutToolUse() {
        String json = """
            {"content":[{"type":"text","text":"plain answer"}]}
            """;
        assertThatThrownBy(() -> AnthropicClient.parseResponse(json, "m", "p"))
            .isInstanceOf(AiExceptions.MalformedModelOutputException.class);
    }

    // -- Gemini --

    @Test
    void geminiParsesFunctionCallArgs() {
        String json = """
            {"modelVersion":"gemini-2.5-flash","candidates":[{"content":{"parts":[
               {"functionCall":{"name":"submit_answers","args":{"answers":[]}}}]}}],
             "usageMetadata":{"promptTokenCount":9,"candidatesTokenCount":2,"totalTokenCount":11}}
            """;
        AiProvider.ChatResponse res = GeminiClient.parseResponse(json, "requested", "gemini");
        assertThat(res.model()).isEqualTo("gemini-2.5-flash");
        assertThat(res.toolArgs()).containsKey("answers");
        assertThat(res.usage().totalTokens()).isEqualTo(11);
    }

    @Test
    void geminiRejectsNoCandidatesIncludingPromptFeedback() {
        String json = """
            {"promptFeedback":{"blockReason":"SAFETY"}}
            """;
        assertThatThrownBy(() -> GeminiClient.parseResponse(json, "m", "p"))
            .isInstanceOf(AiExceptions.MalformedModelOutputException.class)
            .hasMessageContaining("SAFETY");
    }

    @Test
    void geminiRejectsCandidateWithoutFunctionCall() {
        String json = """
            {"candidates":[{"content":{"parts":[{"text":"prose instead of tool call"}]}}]}
            """;
        assertThatThrownBy(() -> GeminiClient.parseResponse(json, "m", "p"))
            .isInstanceOf(AiExceptions.MalformedModelOutputException.class);
    }

    // -- Gemini schema dialect translation --

    @Test
    @SuppressWarnings("unchecked")
    void geminiSchemaTranslatesUnionTypesToNullable() {
        Map<String, Object> schema = Map.of(
            "type", "object",
            "additionalProperties", false,
            "properties", Map.of(
                "points", Map.of("type", List.of("number", "null"), "description", "pts")
            ),
            "required", List.of("points")
        );
        Map<String, Object> out = (Map<String, Object>) GeminiClient.toGeminiSchema(schema);

        assertThat(out).doesNotContainKey("additionalProperties");
        assertThat(out).containsEntry("required", List.of("points"));
        Map<String, Object> points = (Map<String, Object>) ((Map<String, Object>) out.get("properties")).get("points");
        assertThat(points).containsEntry("type", "number");
        assertThat(points).containsEntry("nullable", true);
        assertThat(points).containsEntry("description", "pts");
    }

    @Test
    @SuppressWarnings("unchecked")
    void geminiSchemaRecursesIntoArrayItems() {
        Map<String, Object> schema = Map.of(
            "type", "array",
            "items", Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of("id", Map.of("type", "string", "enum", List.of("a", "b")))
            )
        );
        Map<String, Object> out = (Map<String, Object>) GeminiClient.toGeminiSchema(schema);
        Map<String, Object> items = (Map<String, Object>) out.get("items");
        assertThat(items).doesNotContainKey("additionalProperties");
        Map<String, Object> id = (Map<String, Object>) ((Map<String, Object>) items.get("properties")).get("id");
        assertThat(id).containsEntry("enum", List.of("a", "b"));
    }
}
