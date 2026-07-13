package de.tum.cit.hestia.llm;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

public class HestiaLlmDefaults implements EnvironmentPostProcessor {

    static final String PROPERTY_SOURCE_NAME = "hestiaLlmDefaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> defaults = new HashMap<>();
        defaults.put("spring.ai.openai.base-url", "https://chat-ai.academiccloud.de");
        defaults.put("spring.ai.openai.chat.options.model", "openai-gpt-oss-120b");
        defaults.put("spring.ai.openai.chat.options.temperature", "0.0");
        defaults.put("spring.ai.openai.embedding.options.model", "e5-mistral-7b-instruct");
        defaults.put("spring.ai.openai.chat.options.vision-model", "qwen3.5-27b");
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
    }
}
