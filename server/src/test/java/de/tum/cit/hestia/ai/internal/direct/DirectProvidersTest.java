package de.tum.cit.hestia.ai.internal.direct;

import de.tum.cit.hestia.ai.AiExceptions;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static de.tum.cit.hestia.ai.Provider.ANTHROPIC;
import static de.tum.cit.hestia.ai.Provider.OPENAI;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DirectProvidersTest {

    @Test
    void openAiProviderRequiresOpenAiApiKey() {
        DirectProviders factory = new DirectProviders();
        ReflectionTestUtils.setField(factory, "openaiBaseUrl", "https://openai.example.test/v1");

        assertThatThrownBy(() -> factory.build(OPENAI, "gpt-5.5"))
            .isInstanceOf(AiExceptions.ProviderException.class)
            .hasMessageContaining("OPENAI_API_KEY");
    }

    @Test
    void anthropicProviderRequiresAnthropicApiKey() {
        DirectProviders factory = new DirectProviders();
        ReflectionTestUtils.setField(factory, "anthropicBaseUrl", "https://anthropic.example.test");

        assertThatThrownBy(() -> factory.build(ANTHROPIC, "claude-opus-4-8"))
            .isInstanceOf(AiExceptions.ProviderException.class)
            .hasMessageContaining("ANTHROPIC_API_KEY");
    }

    @Test
    void configuredOnlyWithKey() {
        DirectProviders factory = new DirectProviders();
        assertThat(factory.isConfigured(OPENAI)).isFalse();
        ReflectionTestUtils.setField(factory, "openaiApiKey", "sk-test");
        assertThat(factory.isConfigured(OPENAI)).isTrue();
    }
}
