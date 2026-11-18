package de.tum.cit.hestia.materials.tenancy;

import de.tum.cit.hestia.core.OrgContext;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Executors whose tasks run for the organisation of the thread that submitted them
 * ({@link OrgContext#wrap(Runnable)}). Everything in the module that touches the database from
 * another thread uses these instead of the plain {@link Executors} factories: without an
 * organisation no row is visible and no insert passes.
 */
public final class OrgExecutors {

    private OrgExecutors() {}

    public static ExecutorService bound(ExecutorService delegate) {
        return new Bound(delegate);
    }

    public static ExecutorService newFixedThreadPool(int threads) {
        return bound(Executors.newFixedThreadPool(threads));
    }

    public static ExecutorService newVirtualThreadPerTaskExecutor() {
        return bound(Executors.newVirtualThreadPerTaskExecutor());
    }

    private static final class Bound extends AbstractExecutorService {

        private final ExecutorService delegate;

        Bound(ExecutorService delegate) {
            this.delegate = delegate;
        }

        @Override
        public void execute(Runnable command) {
            delegate.execute(OrgContext.wrap(command));
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }
}
