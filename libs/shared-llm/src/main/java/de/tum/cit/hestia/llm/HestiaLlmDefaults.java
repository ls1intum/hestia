package de.tum.cit.hestia.llm;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

public class HestiaLlmDefaults implements EnvironmentPostProcessor {

    static final String PROPERTY_SOURCE_NAME = "hestiaLlmDefaults";

    /** Activates the TUM AET Logos gateway instead of GWDG SAIA; both speak the OpenAI API. */
    static final String LOGOS_PROFILE = "logos";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> defaults = logosRequested(environment) ? logos() : saia();
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
    }

    private static Map<String, Object> saia() {
        Map<String, Object> defaults = common();
        defaults.put("spring.ai.openai.base-url", "https://chat-ai.academiccloud.de");
        defaults.put("spring.ai.openai.chat.options.model", "openai-gpt-oss-120b");
        defaults.put("spring.ai.openai.chat.options.vision-model", "qwen3.5-27b");
        return defaults;
    }

    /**
     * Logos publishes the same weights under provider-prefixed ids, and its catalogue holds no
     * embedding model, so the embedding default stays as it is: an app that embeds needs SAIA.
     */
    private static Map<String, Object> logos() {
        Map<String, Object> defaults = common();
        defaults.put("spring.ai.openai.base-url", "https://logos.aet.cit.tum.de");
        defaults.put("spring.ai.openai.chat.options.model", "openai/gpt-oss-120b");
        defaults.put("spring.ai.openai.chat.options.vision-model", "Qwen/Qwen3.8-27B");
        return defaults;
    }

    private static Map<String, Object> common() {
        Map<String, Object> defaults = new HashMap<>();
        defaults.put("spring.ai.openai.chat.options.temperature", "0.0");
        defaults.put("spring.ai.openai.embedding.options.model", "e5-mistral-7b-instruct");
        return defaults;
    }

    /**
     * Reads the requested profiles rather than the resolved ones: this runs before configuration
     * files are loaded, so it sees SPRING_PROFILES_ACTIVE, -Dspring.profiles.active and the command
     * line — the ways a provider is chosen for a run — but not a profile named inside a config file.
     */
    private static boolean logosRequested(ConfigurableEnvironment environment) {
        for (String property : new String[] {"spring.profiles.active", "spring.profiles.include"}) {
            String value = environment.getProperty(property);
            if (value != null) {
                for (String profile : value.split(",")) {
                    if (LOGOS_PROFILE.equalsIgnoreCase(profile.trim())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
