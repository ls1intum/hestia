package de.tum.cit.hestia.core.internal.privacy;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the inactivity deletion ({@code hestia.privacy.inactivity.*}).
 *
 * @param enabled         whether the daily run happens at all
 * @param dryRun          only log what would be warned and deleted; write nothing
 * @param warnAfterDays   days without login after which a warning is recorded (ascending)
 * @param deleteAfterDays days without login after which the account is deleted
 * @param notifier        how a warning is delivered besides the notice after login: {@code log}
 *                        (default) or {@code mail} (not wired to any mail server yet)
 */
@ConfigurationProperties("hestia.privacy.inactivity")
record InactivityProperties(boolean enabled, boolean dryRun, List<Integer> warnAfterDays, int deleteAfterDays,
        String notifier) {

    InactivityProperties {
        if (warnAfterDays == null || warnAfterDays.isEmpty()) {
            warnAfterDays = List.of(300, 350);
        }
        warnAfterDays = warnAfterDays.stream().sorted().toList();
        if (deleteAfterDays <= 0) {
            deleteAfterDays = 365;
        }
        if (warnAfterDays.getLast() >= deleteAfterDays) {
            throw new IllegalArgumentException("Every warning must come before the deletion (delete-after-days "
                    + deleteAfterDays + ", warn-after-days " + warnAfterDays + ")");
        }
        if (notifier == null || notifier.isBlank()) {
            notifier = "log";
        }
    }

    /** The last warning's threshold: the account is deleted only after that warning was recorded. */
    int lastWarning() {
        return warnAfterDays.getLast();
    }
}
