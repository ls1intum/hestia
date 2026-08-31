package de.tum.cit.hestia.learninggoalhub.llm;

import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResourceAccessException;

/** Adds jitter to Spring AI's exponential retry schedule so concurrent calls do not retry together. */
@Configuration(proxyBeanMethods = false)
public class AiRetryConfiguration {

    @Bean
    RetryTemplate retryTemplate(SpringAiRetryProperties properties) {
        SpringAiRetryProperties.Backoff backoff = properties.getBackoff();
        return RetryTemplate.builder()
                .maxAttempts(properties.getMaxAttempts())
                .retryOn(TransientAiException.class)
                .retryOn(ResourceAccessException.class)
                .exponentialBackoff(
                        backoff.getInitialInterval(),
                        backoff.getMultiplier(),
                        backoff.getMaxInterval(),
                        true)
                .build();
    }
}
