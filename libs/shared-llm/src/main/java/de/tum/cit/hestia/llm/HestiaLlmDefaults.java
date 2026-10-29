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
 * <p>Chat and vision run on Logos. It serves no {@code /v1/embeddings} endpoint, so there is no
 * embedding default.
 */
public class HestiaLlmDefaults implements EnvironmentPostProcessor {

    static final String PROPERTY_SOURCE_NAME = "hestiaLlmDefaults";

    static final String LOGOS_BASE_URL = "https://logos.aet.cit.tum.de";

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
        return defaults;
    }
}
