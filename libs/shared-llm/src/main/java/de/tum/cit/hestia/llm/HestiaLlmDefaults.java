package de.tum.cit.hestia.llm;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Low-priority LLM defaults: the TUM AET Logos gateway, which speaks the OpenAI API. Anything an
 * app sets itself — properties, environment variables, system properties — still wins.
 *
 * <p>Chat and vision run on Logos. Embeddings do not: Logos serves no {@code /v1/embeddings}
 * endpoint, so the embedding base URL points at GWDG SAIA and an app that embeds supplies a SAIA
 * key under {@code spring.ai.openai.embedding.api-key}.
 */
public class HestiaLlmDefaults implements EnvironmentPostProcessor {

    static final String PROPERTY_SOURCE_NAME = "hestiaLlmDefaults";

    static final String LOGOS_BASE_URL = "https://logos.aet.cit.tum.de";

    static final String SAIA_BASE_URL = "https://chat-ai.academiccloud.de";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults()));
    }

    /** Logos publishes its two models under provider-prefixed ids. */
    private static Map<String, Object> defaults() {
        Map<String, Object> defaults = new HashMap<>();
        defaults.put("spring.ai.openai.base-url", LOGOS_BASE_URL);
        defaults.put("spring.ai.openai.chat.options.model", "openai/gpt-oss-120b");
        defaults.put("spring.ai.openai.chat.options.vision-model", "Qwen/Qwen3.8-27B");
        defaults.put("spring.ai.openai.chat.options.temperature", "0.0");
        defaults.put("spring.ai.openai.embedding.base-url", SAIA_BASE_URL);
        defaults.put("spring.ai.openai.embedding.options.model", "e5-mistral-7b-instruct");
        return defaults;
    }
}
