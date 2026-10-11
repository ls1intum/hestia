package de.tum.cit.hestia.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class HestiaLlmDefaultsTest {

    private final HestiaLlmDefaults processor = new HestiaLlmDefaults();

    @Test
    void appliesLogosDefaultsForChatAndVision() {
        MockEnvironment environment = new MockEnvironment();

        processor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("spring.ai.openai.base-url"))
                .isEqualTo("https://logos.aet.cit.tum.de");
        assertThat(environment.getProperty("spring.ai.openai.chat.options.model"))
                .isEqualTo("openai/gpt-oss-120b");
        assertThat(environment.getProperty("spring.ai.openai.chat.options.vision-model"))
                .isEqualTo("Qwen/Qwen3.8-27B");
        assertThat(environment.getProperty("spring.ai.openai.chat.options.temperature")).isEqualTo("0.0");
    }

    @Test
    void keepsEmbeddingsOnSaia() {
        MockEnvironment environment = new MockEnvironment();

        processor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("spring.ai.openai.embedding.base-url"))
                .isEqualTo("https://chat-ai.academiccloud.de");
        assertThat(environment.getProperty("spring.ai.openai.embedding.options.model"))
                .isEqualTo("e5-mistral-7b-instruct");
    }

    @Test
    void userOverridesTakePrecedenceOverDefaults() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.ai.openai.chat.options.model", "Qwen/Qwen3.8-27B")
                .withProperty("spring.ai.openai.base-url", "http://localhost:11434");

        processor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("spring.ai.openai.chat.options.model"))
                .isEqualTo("Qwen/Qwen3.8-27B");
        assertThat(environment.getProperty("spring.ai.openai.base-url")).isEqualTo("http://localhost:11434");
    }
}
