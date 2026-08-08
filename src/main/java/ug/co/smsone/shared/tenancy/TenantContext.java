package ug.co.smsone.shared.tenancy;

import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The one place a thread's tenant axis is declared, and the one place it is read from
 * (ADR 0010 §3.2). Everything downstream — {@code TenantRoutingDataSource} today, the tenant-scoped
 * caches in Phase 3 — reads {@link #current()} and nothing else.
 *
 * <p><strong>A bare static holder, with no Spring bean anywhere in it, deliberately.</strong>
 * Hibernate instantiates its strategy beans while the {@code EntityManagerFactory} is being built, so
 * a tenant resolver that injected a repository would be a circular-initialization failure at boot —
 * the kind that only shows up once someone wires the resolver into Hibernate. The one Spring type
 * referenced here is {@link TransactionSynchronizationManager}, which is a static thread-bound holder
 * and not a bean: nothing to inject, nothing to initialize early.
 *
 * <p><strong>The trap this class exists to make loud.</strong> The schema is chosen when a connection
 * is <em>borrowed</em>, and Spring binds one connection to a transaction for its whole life. So
 * pinning a tenant inside a {@code @Transactional} method changes nothing about the connection you
 * are already holding: the statements keep running against the previous tenant's {@code search_path}
 * and every row you write lands in the wrong schema, silently. {@link #set(UUID)} therefore
 * <em>throws</em> when a transaction is active, which converts the worst failure this design can
 * produce into an obvious one. Pin first, then open the transaction — that is what {@link #runAs} is
 * for.
 *
 * <p><strong>Clearing.</strong> {@code spring.threads.virtual.enabled} is true, so request handling
 * runs on threads that are never reused and a forgotten {@link #clear()} cannot leak between HTTP
 * requests. It leaks on the pooled platform threads — the scheduler, the {@code @Async} executors,
 * the MCP SDK's threads — where the next unit of work would inherit the last one's tenant. Clear in a
 * {@code finally}, and test the clearing on those paths specifically; the HTTP tests pass either way.
 */
public final class TenantContext {

    private static final ThreadLocal<Tenant> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    /** Never null: an unset thread reads {@link Tenant#ABSENT}, which routes to the empty schema. */
    public static Tenant current() {
        Tenant tenant = CURRENT.get();
        return tenant != null ? tenant : Tenant.ABSENT;
    }

    /**
     * Pins the tenant axis for this thread.
     *
     * @throws IllegalStateException if a transaction is already active — see the class note; the
     *     connection is already bound and this call would be a silent no-op with the previous
     *     tenant's schema still in force.
     */
    public static void set(UUID orgId) {
        pin(Tenant.of(orgId));
    }

    /** Pins any axis. Same transaction rule as {@link #set(UUID)}. */
    public static void set(Tenant tenant) {
        pin(tenant);
    }

    /** Pins the platform axis. Same transaction rule as {@link #set(UUID)}. */
    public static void setPlatform() {
        pin(Tenant.PLATFORM);
    }

    /**
     * Puts back an axis captured earlier with {@link #current()} — the {@code finally} half of a
     * hand-rolled pin/restore (the MCP dispatcher's, the filters'). Never throws, for the reason
     * {@link #clear()} does not, and restores {@link Tenant#ABSENT} as absence rather than as a value.
     */
    public static void restore(Tenant previous) {
        restoreQuietly(previous);
    }

    /**
     * Restores the poison state. Never throws, including inside a transaction: this runs in
     * {@code finally} blocks, where throwing would mask the failure that got us there and — worse —
     * leave a pooled thread pinned to a tenant it no longer serves.
     *
     * <p>{@code remove()} rather than {@code set(null)} so the thread-local entry itself goes; on a
     * pooled platform thread the entry would otherwise outlive every unit of work that ran on it.
     */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * Runs {@code work} on one tenant's axis and restores the previous state — including
     * {@link Tenant#ABSENT} — afterwards.
     *
     * <p>This is the shape a cross-tenant admin write takes (ADR 0010 §5.15): writing another org's
     * rows from a platform route is a deliberate capability and should read like one at the call
     * site. It must wrap the transaction, never sit inside one — {@link #set(UUID)} throws if it
     * does, which is the point.
     */
    public static void runAs(UUID orgId, Runnable work) {
        runWith(new Tenant.Org(orgId), () -> {
            work.run();
            return null;
        });
    }

    /** {@link #runAs(UUID, Runnable)} for work that returns a value. */
    public static <T> T callAs(UUID orgId, Supplier<T> work) {
        return runWith(new Tenant.Org(orgId), work);
    }

    /**
     * Runs {@code work} on the platform axis and restores the previous state. The explicit form every
     * job, worker and startup task needs: off a request thread nothing pins an axis, so without this
     * the first borrow lands in {@code no_tenant} (ADR 0010 §3.4).
     */
    public static void runAsPlatform(Runnable work) {
        runWith(Tenant.PLATFORM, () -> {
            work.run();
            return null;
        });
    }

    /** {@link #runAsPlatform(Runnable)} for work that returns a value. */
    public static <T> T callAsPlatform(Supplier<T> work) {
        return runWith(Tenant.PLATFORM, work);
    }

    private static <T> T runWith(Tenant tenant, Supplier<T> work) {
        Tenant previous = current();
        pin(tenant);
        try {
            return work.get();
        } finally {
            restoreQuietly(previous);
        }
    }

    private static void pin(Tenant tenant) {
        if (tenant == null) {
            throw new IllegalArgumentException("A tenant axis is required — Tenant.ABSENT is a state, not null");
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "TenantContext must be pinned BEFORE the transaction opens: the schema is chosen when the"
                            + " connection is borrowed and the transaction has already bound one, so this call"
                            + " would silently leave every statement on the previous tenant's search_path."
                            + " Pin outside the @Transactional boundary, or wrap it in TenantContext.runAs(...).");
        }
        // ABSENT is stored as absence: the thread-local entry itself goes, so a pooled platform thread
        // carries nothing forward. current() reads the same state either way.
        if (tenant instanceof Tenant.Absent) {
            CURRENT.remove();
        } else {
            CURRENT.set(tenant);
        }
    }

    private static void restoreQuietly(Tenant previous) {
        if (previous == null || previous instanceof Tenant.Absent) {
            CURRENT.remove();
        } else {
            // Not pin(): restoring must not throw. A nested runAs whose body opened a transaction that
            // is still unwinding would otherwise fail here and lose the caller's own axis.
            CURRENT.set(previous);
        }
    }
}
