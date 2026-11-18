package de.tum.cit.hestia.assessment.config;

import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Keeps the assessment API out of every OpenAPI document while {@code app.docs.enabled} is off
 * (the default). The server keeps springdoc running for the other modules, whose documents are
 * public; the assessment API is internal to its client and documented only on request, as the
 * group registered by {@link OpenApiConfig}.
 */
@Configuration
@ConditionalOnProperty(name = "app.docs.enabled", havingValue = "false", matchIfMissing = true)
public class OpenApiHidden {

    @Bean
    public GlobalOpenApiCustomizer assessmentHiddenFromOpenApi() {
        return openApi -> {
            if (openApi.getPaths() != null) {
                openApi.getPaths().keySet().removeIf(path -> path.startsWith("/examlense/"));
            }
        };
    }
}
