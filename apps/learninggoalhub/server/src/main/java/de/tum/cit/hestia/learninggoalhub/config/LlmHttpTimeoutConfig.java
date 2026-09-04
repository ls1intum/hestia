package de.tum.cit.hestia.learninggoalhub.config;

import java.time.Duration;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;

/**
 * Gives every LLM call a deadline.
 *
 * <p>Without one a dead connection hangs a run forever. Measured: a nineteen-lecture extraction sat
 * for three and a half hours with all four worker threads parked on a {@code CompletableFuture} that
 * could never complete — the laptop had slept, the sockets died with it, and the responses simply
 * never came. Nothing recovers from that on its own. The retry ladder cannot, because a retry needs
 * a response to react to, and a run that never fails is never marked failed either: it holds the
 * progress tracker's single slot and blocks the next extraction until the server is restarted.
 *
 * <p>The read timeout is generous because a legitimate extraction call is slow — a whole unit of
 * slide text against a large model, sometimes minutes when the provider is loaded. It exists to turn
 * a hang into a failure, not to police latency: past the deadline the call raises an IOException,
 * the session fails on its own terms, and the run reaches a terminal state a person can act on.
 */
@Configuration
public class LlmHttpTimeoutConfig {

    private final Duration connectTimeout;
    private final Duration readTimeout;

    public LlmHttpTimeoutConfig(
            @Value("${hestia.llm.connect-timeout:20s}") Duration connectTimeout,
            @Value("${hestia.llm.read-timeout:10m}") Duration readTimeout) {
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    @Bean
    RestClientCustomizer llmRestClientTimeouts() {
        return builder -> builder.requestFactory(ClientHttpRequestFactories.get(
                ClientHttpRequestFactorySettings.DEFAULTS
                        .withConnectTimeout(connectTimeout)
                        .withReadTimeout(readTimeout)));
    }
}
