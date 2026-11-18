package de.tum.cit.hestia.core;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.springframework.core.task.TaskDecorator;

/**
 * The organisation (tenant) the current thread works for. Filled from the logged-in user for every
 * request; Hibernate filters every entity by it ({@link OrgScoped}) and the database connections
 * carry it for row-level security. Work outside the request thread must carry it along: wrap the
 * task ({@link #wrap(Runnable)}, {@link #wrap(Callable)}) or give the executor {@link #taskDecorator()}.
 * Without an organisation no row is visible and no insert passes.
 */
public final class OrgContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private OrgContext() {}

    /** The current organisation, if the thread has one. */
    public static Optional<UUID> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** The current organisation; fails if the thread has none. */
    public static UUID require() {
        UUID orgId = CURRENT.get();
        if (orgId == null) {
            throw new IllegalStateException("No organisation bound to this thread");
        }
        return orgId;
    }

    /** Binds {@code orgId} (may be null) to this thread and returns what was bound before. */
    public static UUID set(UUID orgId) {
        UUID previous = CURRENT.get();
        if (orgId == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(orgId);
        }
        return previous;
    }

    /** Runs {@code task} for {@code orgId} and restores the thread's previous organisation. */
    public static void run(UUID orgId, Runnable task) {
        UUID previous = set(orgId);
        try {
            task.run();
        } finally {
            set(previous);
        }
    }

    /** Calls {@code task} for {@code orgId} and restores the thread's previous organisation. */
    public static <T> T call(UUID orgId, Callable<T> task) throws Exception {
        UUID previous = set(orgId);
        try {
            return task.call();
        } finally {
            set(previous);
        }
    }

    /** {@code task} bound to the organisation of the calling thread, for running elsewhere. */
    public static Runnable wrap(Runnable task) {
        UUID orgId = CURRENT.get();
        return () -> run(orgId, task);
    }

    /** {@code task} bound to the organisation of the calling thread, for running elsewhere. */
    public static <T> Callable<T> wrap(Callable<T> task) {
        UUID orgId = CURRENT.get();
        return () -> call(orgId, task);
    }

    /** For executors: every task runs for the organisation of the thread that submitted it. */
    public static TaskDecorator taskDecorator() {
        return OrgContext::wrap;
    }
}
