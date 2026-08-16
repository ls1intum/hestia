package app.ai;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static app.ai.ProviderKind.ANTHROPIC;
import static app.ai.ProviderKind.OPENAI;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiProviderFactoryTest {

    @Test
    void openAiProviderRequiresOpenAiApiKey() {
        AiProviderFactory factory = new AiProviderFactory();
        ReflectionTestUtils.setField(factory, "openaiBaseUrl", "https://api.openai.com/v1");

        assertThatThrownBy(() -> factory.build(OPENAI, "gpt-5.5"))
            .isInstanceOf(AiExceptions.ProviderException.class)
            .hasMessageContaining("OPENAI_API_KEY");
    }

    @Test
    void anthropicProviderRequiresAnthropicApiKey() {
        AiProviderFactory factory = new AiProviderFactory();
        ReflectionTestUtils.setField(factory, "anthropicBaseUrl", "https://api.anthropic.com");

        assertThatThrownBy(() -> factory.build(ANTHROPIC, "claude-opus-4-8"))
            .isInstanceOf(AiExceptions.ProviderException.class)
            .hasMessageContaining("ANTHROPIC_API_KEY");
    }
}
