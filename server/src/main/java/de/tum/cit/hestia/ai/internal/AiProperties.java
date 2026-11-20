package de.tum.cit.hestia.ai.internal;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under {@code hestia.ai} that are not provider credentials.
 *
 * @param jobLimits jobs per user and rolling 24 hours, by purpose; 0 or absent = off.
 */
@ConfigurationProperties("hestia.ai")
record AiProperties(Map<String, Integer> jobLimits) {

    AiProperties {
        jobLimits = jobLimits == null ? Map.of() : Map.copyOf(jobLimits);
    }

    int limitFor(String purpose) {
        return jobLimits.getOrDefault(purpose, 0);
    }
}
