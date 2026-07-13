package app.ai;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderRequestShapeTest {

    private static final AiProvider.Tool TOOL = new AiProvider.Tool(
        "submit_answers",
        "Submit answers.",
        Map.of("type", "object", "properties", Map.of("answers", Map.of("type", "array")))
    );

    @Test
    @SuppressWarnings("unchecked")
    void openAiResponsesBuildsTextSolverRequestWithForcedTool() {
        Map<String, Object> body = OpenAiResponsesClient.buildBody(
            "gpt-5.5",
            new AiProvider.ChatRequest("system", new AiProvider.TextContent("solve this"), TOOL)
        );

        assertThat(body.get("model")).isEqualTo("gpt-5.5");
        assertThat(body.get("instructions")).isEqualTo("system");
        assertThat(body.get("tool_choice")).isEqualTo(Map.of("type", "function", "name", "submit_answers"));

        List<Map<String, Object>> input = (List<Map<String, Object>>) body.get("input");
        Map<String, Object> user = input.getFirst();
        List<Map<String, Object>> content = (List<Map<String, Object>>) user.get("content");
        assertThat(content.getFirst()).containsEntry("type", "input_text").containsEntry("text", "solve this");
    }

    @Test
    @SuppressWarnings("unchecked")
    void openAiResponsesBuildsPdfParserRequestWithInputFile() {
        Map<String, Object> body = OpenAiResponsesClient.buildBody(
            "gpt-5.5",
            new AiProvider.ChatRequest(
                "system",
                new AiProvider.MultipartContent(List.of(
                    new AiProvider.TextPart("Extract the exam."),
                    new AiProvider.FilePart("exam.pdf", "JVBERi0x", "application/pdf")
                )),
                TOOL
            )
        );

        List<Map<String, Object>> input = (List<Map<String, Object>>) body.get("input");
        Map<String, Object> user = input.getFirst();
        List<Map<String, Object>> content = (List<Map<String, Object>>) user.get("content");
        assertThat(content.get(1))
            .containsEntry("type", "input_file")
            .containsEntry("filename", "exam.pdf")
            .containsEntry("file_data", "data:application/pdf;base64,JVBERi0x");
    }

    @Test
    @SuppressWarnings("unchecked")
    void anthropicBuildsTextSolverRequestWithForcedTool() {
        Map<String, Object> body = AnthropicClient.buildBody(
            "claude-opus-4-8",
            new AiProvider.ChatRequest("system", new AiProvider.TextContent("solve this"), TOOL)
        );

        assertThat(body.get("model")).isEqualTo("claude-opus-4-8");
        assertThat(body.get("system")).isEqualTo("system");
        assertThat(body.get("tool_choice")).isEqualTo(Map.of("type", "tool", "name", "submit_answers"));

        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");
        Map<String, Object> user = messages.getFirst();
        List<Map<String, Object>> content = (List<Map<String, Object>>) user.get("content");
        assertThat(content.getFirst()).containsEntry("type", "text").containsEntry("text", "solve this");
    }

    @Test
    @SuppressWarnings("unchecked")
    void anthropicBuildsPdfParserRequestWithDocumentBlock() {
        Map<String, Object> body = AnthropicClient.buildBody(
            "claude-opus-4-8",
            new AiProvider.ChatRequest(
                "system",
                new AiProvider.MultipartContent(List.of(
                    new AiProvider.TextPart("Extract the exam."),
                    new AiProvider.FilePart("exam.pdf", "JVBERi0x", "application/pdf")
                )),
                TOOL
            )
        );

        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");
        Map<String, Object> user = messages.getFirst();
        List<Map<String, Object>> content = (List<Map<String, Object>>) user.get("content");
        Map<String, Object> document = content.get(1);
        Map<String, Object> source = (Map<String, Object>) document.get("source");

        assertThat(document).containsEntry("type", "document");
        assertThat(source)
            .containsEntry("type", "base64")
            .containsEntry("media_type", "application/pdf")
            .containsEntry("data", "JVBERi0x");
    }
}
