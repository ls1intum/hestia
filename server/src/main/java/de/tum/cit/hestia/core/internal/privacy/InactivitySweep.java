package de.tum.cit.hestia.core.internal.privacy;

import de.tum.cit.hestia.core.OrgContext;
import de.tum.cit.hestia.core.internal.course.CourseRelease;
import de.tum.cit.hestia.core.internal.identity.AppUser;
import de.tum.cit.hestia.core.internal.identity.AppUserRepository;
import de.tum.cit.hestia.core.internal.identity.Organisation;
import de.tum.cit.hestia.core.internal.identity.OrganisationRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One run of the inactivity deletion over all organisations:
 * <ul>
 *   <li>a user without login for at least a warning threshold (default 300 and 350 days) gets a
 *       warning for the current inactive period, once per threshold; the clients show it after the
 *       next login, and the {@link InactivityNotifier} is called after the commit;</li>
 *   <li>a user without login for {@code delete-after-days} (default 365) is deleted with their solo
 *       courses ({@link CourseRelease}); the database removes their memberships, consents and
 *       user-private objects. The deletion waits until the last warning has stood for the gap between
 *       the last threshold and the deletion (default 15 days), so nobody is deleted unwarned, also
 *       after the job was off for a while.</li>
 * </ul>
 * A login moves {@code last_seen_at} and so starts a new period: older warnings no longer count.
 * Each organisation runs with its {@link OrgContext} (tenant filter and row-level security), each
 * user in a transaction of its own that first locks the user row and checks again. Only one
 * instance runs at a time (Postgres advisory lock). In a dry run nothing is written; the run only
 * logs what it would do.
 */
@Service
class InactivitySweep {

    private static final Logger log = LoggerFactory.getLogger(InactivitySweep.class);

    /** Advisory lock key of the sweep (any constant unique within the database). */
    static final long LOCK_KEY = 0x48455354_49413031L;

    private final InactivityProperties properties;
    private final OrganisationRepository organisations;
    private final AppUserRepository users;
    private final InactivityWarningRepository warnings;
    private final CourseRelease courseRelease;
    private final InactivityNotifier notifier;
    private final TransactionTemplate tx;
    private final DataSource dataSource;

    InactivitySweep(InactivityProperties properties, OrganisationRepository organisations, AppUserRepository users,
            InactivityWarningRepository warnings, CourseRelease courseRelease, InactivityNotifier notifier,
            TransactionTemplate tx, DataSource dataSource) {
        this.properties = properties;
        this.organisations = organisations;
        this.users = users;
        this.warnings = warnings;
        this.courseRelease = courseRelease;
        this.notifier = notifier;
        this.tx = tx;
        this.dataSource = dataSource;
    }

    /** What a run did (or, in a dry run, would have done). {@code skipped}: another instance was running. */
    record Report(boolean dryRun, boolean skipped, int warned, int deleted, int postponed, int failed) {}

    Report run(Instant at) {
        // Whole seconds: the database rounds to microseconds, which must not push a stored warning
        // past "now" when the next run compares with it.
        Instant now = at.truncatedTo(ChronoUnit.SECONDS);
        try (Connection lockConnection = dataSource.getConnection()) {
            if (!advisoryLock(lockConnection, "pg_try_advisory_lock")) {
                log.info("Inactivity sweep skipped: another instance is running");
                return new Report(properties.dryRun(), true, 0, 0, 0, 0);
            }
            try {
                return sweepAll(now);
            } finally {
                advisoryLock(lockConnection, "pg_advisory_unlock");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Inactivity sweep could not take its lock", e);
        }
    }

    private Report sweepAll(Instant now) {
        Counts counts = new Counts();
        List<UUID> orgIds = organisations.findAll().stream().map(Organisation::getId).toList();
        for (UUID orgId : orgIds) {
            OrgContext.run(orgId, () -> sweepOrganisation(orgId, now, counts));
        }
        Report report = new Report(properties.dryRun(), false, counts.warned.get(), counts.deleted.get(),
                counts.postponed.get(), counts.failed.get());
        log.info("Inactivity sweep{}: {} warned, {} deleted, {} deletions waiting for a warning, {} failed",
                properties.dryRun() ? " (dry run, nothing written)" : "", report.warned(), report.deleted(),
                report.postponed(), report.failed());
        return report;
    }

    private void sweepOrganisation(UUID orgId, Instant now, Counts counts) {
        Instant firstThreshold = now.minus(Duration.ofDays(properties.warnAfterDays().getFirst()));
        List<UUID> candidates = tx.execute(status -> users.findInactiveSince(firstThreshold).stream()
                .map(AppUser::getId).toList());
        for (UUID userId : candidates) {
            try {
                tx.executeWithoutResult(status -> sweepUser(orgId, userId, now, counts));
            } catch (RuntimeException e) {
                counts.failed.incrementAndGet();
                log.error("Inactivity sweep failed for user {} of organisation {}", userId, orgId, e);
            }
        }
    }

    private void sweepUser(UUID orgId, UUID userId, Instant now, Counts counts) {
        // A login of the same user waits for this lock; one that came first has moved last_seen_at.
        AppUser user = users.lockById(userId).orElse(null);
        if (user == null) {
            return;
        }
        Instant lastSeen = user.lastActivity();
        long days = Duration.between(lastSeen, now).toDays();
        List<InactivityWarning> issued = warnings.findByUserIdAndLastSeenAt(userId, lastSeen);

        if (days >= properties.deleteAfterDays()) {
            Instant deletableFrom = lastWarningAt(issued)
                    .map(at -> at.plus(Duration.ofDays(properties.deleteAfterDays() - properties.lastWarning())))
                    .orElse(null);
            if (deletableFrom != null && !deletableFrom.isAfter(now)) {
                delete(user, days, counts);
                return;
            }
            counts.postponed.incrementAndGet();
        }

        int threshold = properties.warnAfterDays().stream().filter(t -> days >= t).reduce((a, b) -> b).orElse(-1);
        boolean alreadyWarned = issued.stream().anyMatch(w -> w.getAfterDays() >= threshold);
        if (threshold < 0 || alreadyWarned) {
            return;
        }
        Instant deletionDue = max(lastSeen.plus(Duration.ofDays(properties.deleteAfterDays())),
                threshold == properties.lastWarning()
                        ? now.plus(Duration.ofDays(properties.deleteAfterDays() - properties.lastWarning()))
                        : now);
        if (properties.dryRun()) {
            log.info("Dry run: would warn user {} of organisation {} ({} days without login, threshold {})",
                    userId, orgId, days, threshold);
            counts.warned.incrementAndGet();
            return;
        }
        warnings.save(new InactivityWarning(userId, threshold, lastSeen, now));
        counts.warned.incrementAndGet();
        InactivityNotifier.Warning warning =
                new InactivityNotifier.Warning(orgId, userId, threshold, lastSeen, deletionDue);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    notifier.warn(warning);
                } catch (RuntimeException e) {
                    log.warn("Inactivity notifier failed for user {}: {}", userId, e.getMessage());
                }
            }
        });
    }

    private void delete(AppUser user, long days, Counts counts) {
        if (properties.dryRun()) {
            CourseRelease.Outcome preview = courseRelease.preview(user.getId());
            log.info("Dry run: would delete user {} ({} days without login): {} courses deleted, {} kept, "
                            + "{} owners promoted", user.getId(), days, preview.deletedCourses(),
                    preview.keptCourses(), preview.ownersPromoted());
            counts.deleted.incrementAndGet();
            return;
        }
        CourseRelease.Outcome outcome = courseRelease.release(user.getId());
        users.delete(user);
        users.flush();
        counts.deleted.incrementAndGet();
        log.info("Deleted user {} after {} days without login: {} courses deleted, {} kept, {} owners promoted",
                user.getId(), days, outcome.deletedCourses(), outcome.keptCourses(), outcome.ownersPromoted());
    }

    /** When the last threshold's warning of this period was recorded, if it was. */
    private Optional<Instant> lastWarningAt(List<InactivityWarning> issued) {
        return issued.stream().filter(w -> w.getAfterDays() >= properties.lastWarning())
                .map(InactivityWarning::getCreatedAt).min(Instant::compareTo);
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static boolean advisoryLock(Connection connection, String function) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select " + function + "(?)")) {
            statement.setLong(1, LOCK_KEY);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private static final class Counts {
        final AtomicInteger warned = new AtomicInteger();
        final AtomicInteger deleted = new AtomicInteger();
        final AtomicInteger postponed = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
    }
}
