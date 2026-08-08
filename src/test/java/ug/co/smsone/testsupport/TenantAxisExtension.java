package ug.co.smsone.testsupport;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import ug.co.smsone.shared.tenancy.TenantContext;

/**
 * Declares the platform axis on the test thread before each test and takes it off afterwards
 * (ADR 0010 §3.4). Carried by {@link AbstractIntegrationTest}, and by the three
 * {@code @ApplicationModuleTest} classes that bootstrap their own context instead of extending it.
 *
 * <p><strong>Why the suite needs this at all.</strong> {@code TenantRoutingDataSource} sets
 * {@code search_path} on every connection borrow from {@link TenantContext}, and the default state —
 * absent — resolves to the empty {@code no_tenant} schema. A test method is not a request: no filter
 * ran above it, so nothing has declared an axis, and the first {@code JdbcTemplate} or repository call
 * on the test thread fails with {@code relation "…" does not exist}. 89 of the 140 test classes do
 * database work on the test thread.
 *
 * <p><strong>Why an extension rather than a {@code @BeforeEach} on the base class.</strong> Ordering.
 * A base-class {@code @BeforeEach} runs before a subclass's, but not before a {@code @BeforeEach}
 * declared on the class itself — and several classes reset tables in exactly such a method
 * ({@code NotificationDeliveryTest.resetQueue}). Extension callbacks run before <em>all</em>
 * {@code @BeforeEach} methods and after all {@code @AfterEach} methods, which is the bracket the pin
 * needs. It also means the three module tests get the same implementation rather than a copy.
 *
 * <p><strong>Why PLATFORM and not a tenant.</strong> In Phase 1 every routed state resolves to the one
 * schema the 55 tables already live in, so the pin is behaviourally a no-op and PLATFORM is the honest
 * name for "the harness, which is not any tenant". Phase 2 splits the schemas and this stops being
 * free: fixtures that write tenant-tier rows will have to say which tenant, with
 * {@code TenantContext.runAs(orgId, …)} around the seeding. Doing it now, while it costs nothing, is
 * what leaves that migration a search for seed calls rather than a debugging session.
 *
 * <p><strong>What this must never grow into.</strong> The pin is on the test thread only. It must not
 * be propagated onto the {@code @Async} executor, the scheduler's pool, or any worker thread — 30
 * {@code @ApplicationModuleListener}s and 14 {@code @SchedulerLock} jobs run there, every one of which
 * owes the codebase its own axis (from the event's {@code orgId}, or {@code runAsPlatform}). A harness
 * that propagated would make all 44 of those obligations unfalsifiable at a stroke, and ADR 0010 §3.2
 * refuses a {@code ThreadLocalAccessor} for the same reason.
 */
public final class TenantAxisExtension implements BeforeEachCallback, AfterEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        // getDeclaredAnnotation, not findAnnotation: an opt-out is a claim a class makes at its own
        // declaration and must never arrive through a superclass or an enclosing class (see NoTenantAxis).
        if (context.getRequiredTestClass().getDeclaredAnnotation(NoTenantAxis.class) != null) {
            // Leave the thread absent. The class under test declares its own axis, or fails closed —
            // which is the assertion it was written to make.
            return;
        }
        // Throws if a transaction is somehow already open, which is the right failure: the connection
        // would already be bound and this pin would be a silent no-op. Nothing in the suite is
        // @Transactional today; the day something is, TenantContext's own message explains it.
        TenantContext.setPlatform();
    }

    @Override
    public void afterEach(ExtensionContext context) {
        // Unconditional, including for opted-out classes: a test that pinned an axis of its own must not
        // hand it to the next test on this thread. JUnit reuses the platform thread across classes, so
        // this is the same pooled-thread leak the production code clears for. clear() never throws.
        TenantContext.clear();
    }
}
