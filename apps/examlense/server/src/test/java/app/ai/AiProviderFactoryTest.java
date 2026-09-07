package app.ai;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static app.ai.ProviderKind.ANTHROPIC;
import static app.ai.ProviderKind.OPENAI;
import static app.ai.ProviderKind.RETIRED;
import static org.assertj.core.api.Assertions.assertThat;
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

    @Test
    void retiredModelIsRefusedRatherThanCalled() {
        AiProviderFactory factory = new AiProviderFactory();

        assertThatThrownBy(() -> factory.build(RETIRED, "qwen3.6-35b-a3b"))
            .isInstanceOf(AiExceptions.ProviderException.class)
            .hasMessage(AiProviderFactory.RETIRED_MESSAGE);
    }

    /**
     * The status matters as much as the throw. A transient classification would
     * send the parse fallback to GPT-5.5 and re-enter the solve retry loop, so an
     * exam would quietly be answered by a model other than the one it records.
     */
    @Test
    void retiredModelFailureIsNotTransient() {
        AiProviderFactory factory = new AiProviderFactory();

        AiExceptions.ProviderException e = org.assertj.core.api.Assertions.catchThrowableOfType(
            () -> factory.build(RETIRED, "qwen3.6-35b-a3b"), AiExceptions.ProviderException.class);

        assertThat(e.status()).isEqualTo(410);
        assertThat(AiExceptions.isTransient(e)).isFalse();
    }
}
