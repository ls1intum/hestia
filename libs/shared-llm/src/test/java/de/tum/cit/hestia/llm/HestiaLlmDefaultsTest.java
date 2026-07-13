package de.tum.cit.hestia.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class HestiaLlmDefaultsTest {

    private final HestiaLlmDefaults processor = new HestiaLlmDefaults();

    @Test
    void appliesSaiaDefaults() {
        MockEnvironment environment = new MockEnvironment();

        processor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("spring.ai.openai.base-url"))
                .isEqualTo("https://chat-ai.academiccloud.de");
        assertThat(environment.getProperty("spring.ai.openai.chat.options.model"))
                .isEqualTo("openai-gpt-oss-120b");
        assertThat(environment.getProperty("spring.ai.openai.embedding.options.model"))
                .isEqualTo("e5-mistral-7b-instruct");
    }

    @Test
    void userOverridesTakePrecedenceOverDefaults() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.ai.openai.chat.options.model", "qwen-2.5-72b-instruct");

        processor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("spring.ai.openai.chat.options.model"))
                .isEqualTo("qwen-2.5-72b-instruct");
    }
}
