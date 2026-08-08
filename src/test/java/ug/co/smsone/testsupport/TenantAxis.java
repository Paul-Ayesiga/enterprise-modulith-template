package ug.co.smsone.testsupport;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import ug.co.smsone.shared.tenancy.Tenant;
import ug.co.smsone.shared.tenancy.TenantContext;

/**
 * The two things a test needs that {@link TenantContext} deliberately does not offer: a way to run one
 * statement with the axis taken <em>off</em>, and a real pooled platform thread to run it on.
 *
 * <p>Both exist to make a specific class of production bug reachable from a test.
 * {@code spring.threads.virtual.enabled} is true, so an HTTP request runs on a thread that is never
 * reused: delete {@code CurrentUserFilter}'s {@code finally} and every MockMvc test in the suite still
 * passes, because there is no next request on that thread to hand the leak to. The leak — and the
 * missing pin — live on POOLED PLATFORM threads: the scheduler behind the 14 {@code @SchedulerLock}
 * jobs, the {@code @Async} executor behind the 30 {@code @ApplicationModuleListener}s, the three
 * workers, and the MCP SDK's. Only a test on one of those can tell the difference.
 */
public final class TenantAxis {

    /**
     * One thread, reused across submissions, and platform rather than virtual — both properties are
     * load-bearing. A per-task thread would make every "the next unit of work inherits nothing"
     * assertion vacuously true, and {@code Executors.newSingleThreadExecutor} is chosen precisely
     * because {@code spring.threads.virtual.enabled} does not reach it.
     *
     * <p>Daemon so it never holds the JVM open, and shared for the whole run so a second call really
     * does land on the thread the first one left behind. {@code TenantAxisHarnessTest} asserts both.
     */
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "tenant-axis-pool");
        thread.setDaemon(true);
        return thread;
    });

    private TenantAxis() {}

    /**
     * Runs {@code work} with no axis declared, then puts back whatever was there — normally the
     * harness's platform pin.
     *
     * <p>This is the one-line form of {@link NoTenantAxis} for a class that needs it in one place only:
     * a test whose fixtures and assertions are ordinary database work, but whose <em>subject</em> is a
     * job, worker or listener that must declare its own axis. Wrap the drive call —
     * {@code withNoAxis(job::purgeExpiredInboxRows)} — and the test now fails when the job's
     * {@code runAsPlatform} is deleted, instead of quietly borrowing the harness's.
     *
     * <p>It restores rather than clears, so the rest of the test method keeps working; a bare
     * {@code TenantContext.clear()} in the middle of a test would poison every line after it for a
     * reason nobody would look for.
     */
    public static void withNoAxis(ThrowingRunnable work) throws Exception {
        callWithNoAxis(() -> {
            work.run();
            return null;
        });
    }

    /** {@link #withNoAxis(ThrowingRunnable)} for work that returns a value. */
    public static <T> T callWithNoAxis(Callable<T> work) throws Exception {
        Tenant previous = TenantContext.current();
        TenantContext.clear();
        try {
            return work.call();
        } finally {
            TenantContext.restore(previous);
        }
    }

    /**
     * Runs {@code work} on the shared pooled platform thread and waits for it.
     *
     * <p>That thread is never pinned by the harness, so work arrives there with no axis — which is
     * exactly the state the scheduler and the {@code @Async} executor hand their tasks in production.
     * Submit twice to assert the second task inherits nothing from the first; that is the only shape in
     * which the {@code finally} that clears can be proved to matter.
     */
    public static void onPooledThread(ThrowingRunnable work) throws Exception {
        callOnPooledThread(() -> {
            work.run();
            return null;
        });
    }

    /** {@link #onPooledThread(ThrowingRunnable)} for work that returns a value. */
    public static <T> T callOnPooledThread(Callable<T> work) throws Exception {
        try {
            return POOL.submit(work).get();
        } catch (ExecutionException failure) {
            // Unwrap, or every assertion about what the work threw has to reach through an
            // ExecutionException that says nothing about the tenancy failure underneath it.
            if (failure.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw failure;
        }
    }

    /** A {@code Runnable} that may throw — job and worker entry points routinely do. */
    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }
}
