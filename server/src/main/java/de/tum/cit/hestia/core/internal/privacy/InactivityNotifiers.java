package de.tum.cit.hestia.core.internal.privacy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The {@link InactivityNotifier} implementations. Exactly one is active, chosen by
 * {@code hestia.privacy.inactivity.notifier}.
 */
@Configuration(proxyBeanMethods = false)
class InactivityNotifiers {

    private static final Logger log = LoggerFactory.getLogger(InactivityNotifiers.class);

    /** Default: the notice after login is the only delivery; the warning is logged (ids only). */
    @Bean
    @ConditionalOnProperty(name = "hestia.privacy.inactivity.notifier", havingValue = "log", matchIfMissing = true)
    InactivityNotifier logInactivityNotifier() {
        return warning -> log.info("Inactivity warning ({} days) recorded for user {} of organisation {}; "
                        + "deletion due from {}", warning.afterDays(), warning.userId(), warning.orgId(),
                warning.deletionDueAt());
    }

    /**
     * E-mail delivery: deliberately not implemented. Hestia stores no e-mail address (the login's
     * address stays in the session) and no mail server is configured, so turning it on needs a
     * decision first: which address, which sender, which mail server. Until then selecting it fails at
     * startup instead of silently sending nothing.
     */
    @Bean
    @ConditionalOnProperty(name = "hestia.privacy.inactivity.notifier", havingValue = "mail")
    InactivityNotifier mailInactivityNotifier() {
        throw new IllegalStateException("hestia.privacy.inactivity.notifier=mail is not available yet: "
                + "e-mail delivery of inactivity warnings needs a stored address and a mail server");
    }
}
