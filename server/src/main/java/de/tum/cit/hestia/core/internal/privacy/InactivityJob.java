package de.tum.cit.hestia.core.internal.privacy;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Starts the {@link InactivitySweep} once a day ({@code hestia.privacy.inactivity.cron}, default
 * 03:30 server time). Off with {@code hestia.privacy.inactivity.enabled=false}.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(InactivityProperties.class)
class InactivityJob {

    private static final Logger log = LoggerFactory.getLogger(InactivityJob.class);

    private final InactivityProperties properties;
    private final InactivitySweep sweep;
    private final Clock clock = Clock.systemUTC();

    InactivityJob(InactivityProperties properties, InactivitySweep sweep) {
        this.properties = properties;
        this.sweep = sweep;
    }

    @Scheduled(cron = "${hestia.privacy.inactivity.cron:0 30 3 * * *}")
    void daily() {
        if (!properties.enabled()) {
            return;
        }
        try {
            sweep.run(clock.instant());
        } catch (RuntimeException e) {
            log.error("Inactivity sweep failed", e);
        }
    }
}
