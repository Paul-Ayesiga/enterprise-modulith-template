package ug.co.smsone.shared.tenancy;

import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ug.co.smsone.shared.tenancy.placement.TenantPlacement;

/**
 * <strong>Runs one statement where its rows actually are, when the connection the caller is holding
 * cannot reach them</strong> (ADR 0011 §5.1). This is the seam Phase 7 needs and Phase 7 shipped
 * without: the router made "which database" a per-tenant answer, and every platform-tier write issued
 * from a tenant-axis thread had been correct only because there was one database to be wrong about.
 *
 * <h2>The defect this exists to close</h2>
 *
 * <p>{@code platform.idempotency_key}, {@code platform.queue_signal}, {@code platform.event_inbox},
 * {@code platform.notification_delivery} and the null-org half of {@code audit_log} live on PRIMARY and
 * nowhere else (ADR 0011 §5). A request thread carries the tenant's axis from {@code CurrentUserFilter}
 * ({@code @Order(-1)}) onwards, so every one of those statements has been running on the tenant's
 * connection. While every tenant is on primary that is invisible — the schema is right there, named
 * explicitly, exactly as AGENTS §1 requires. The moment a tenant is served from another database it is
 * {@code relation "platform.idempotency_key" does not exist}, because the remote's {@code platform}
 * schema deliberately holds only {@code event_publication} (§5.1's tripwire, and the tripwire worked).
 *
 * <h2>Why this cannot just be {@code TenantContext.callAsPlatform}</h2>
 *
 * <p>Because most of these call sites are inside a transaction, and {@link TenantContext} throws
 * there — on purpose, because the connection is already bound and a pin after the borrow is the silent
 * misroute the whole design exists to prevent. A conversion that pins blind is not a conversion; it is
 * an {@code IllegalStateException} on the first enqueue. So the hop has to SUSPEND the caller's
 * transaction first, which is what {@link TransactionDefinition#PROPAGATION_NOT_SUPPORTED} does:
 * inside its callback the connection holder is unbound and {@code isActualTransactionActive()} is
 * false, so the pin is legal and the borrow that follows is a fresh one from the pool the axis names.
 * The caller's transaction is resumed on the way out, untouched.
 *
 * <h2>The one rule that keeps this safe: it does nothing when nothing is wrong</h2>
 *
 * <p>{@link #runOnPlatform} and {@link #runInHomeOf} compare the caller's DATABASE with the target's
 * and, when they are the same, simply run the work — same connection, same transaction, same
 * statement, byte for byte what happens today. On every deployment with no remote datasource
 * configured that is <em>every</em> call, so this class changes nothing about the shipped system and
 * costs one {@code ConcurrentHashMap} lookup ({@link TenantRoutes}' memo, which
 * {@link SplitTables#homeOf} already pays on the same call sites). The hop — a suspended transaction
 * and a second connection — is reachable only by a tenant that is genuinely on another database,
 * where the alternative is a hard failure.
 *
 * <p><strong>Which means the atomicity that survives is exactly the atomicity that is achievable.</strong>
 * A co-located tenant keeps every guarantee it has: the queue signal still commits with the rows it
 * indexes, the {@code event_inbox} row still commits with the fan-out it de-duplicates, the audit row
 * still commits with the change it describes. A tenant on another database cannot have those, because
 * there is no cross-database atomic write and this project refuses XA. It gets a separate transaction
 * and a named partial-failure story per call site — the trade ADR 0011 §5.1 and §8 item 5 state, made
 * mechanical here rather than left to each call site to remember.
 *
 * <h2>Cost, stated because it is real</h2>
 *
 * <p>A hop holds TWO connections at once for the length of the inner statement: the caller's (still
 * checked out to the suspended transaction) and the borrow this makes. Only remote tenants pay it, and
 * only on the platform-tier half of their work, but it is a term in ADR 0011 §8 item 6's connection
 * arithmetic and it is why the hop is conditional rather than unconditional.
 */
@Component
public class CrossDatabaseWrites {

    /**
     * Suspends whatever transaction the caller is in and runs the callback outside it. Not
     * {@code REQUIRES_NEW}: a new transaction would begin — and bind a connection — <em>before</em> the
     * callback could pin an axis, which is the pin-after-borrow failure again, one layer up. The inner
     * work runs on an autocommitted connection from the pool its own axis names, which is what makes it
     * "a separate transaction on another database" in the sense §5.1 means.
     */
    private final TransactionTemplate suspended;

    CrossDatabaseWrites(PlatformTransactionManager transactionManager) {
        this.suspended = new TransactionTemplate(transactionManager);
        this.suspended.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    /**
     * Runs a PLATFORM-TIER statement against the platform database, from any axis and from inside or
     * outside a transaction. The idiom for every {@code platform.<table>} write that a tenant-axis
     * thread can reach: {@code crossDatabase.runOnPlatform(() -> signals.raise(queue, scope))}.
     */
    public void runOnPlatform(Runnable work) {
        callOnPlatform(() -> {
            work.run();
            return null;
        });
    }

    /** {@link #runOnPlatform} for work that returns a value. */
    public <T> T callOnPlatform(Supplier<T> work) {
        return in(null, work);
    }

    /**
     * Runs a SPLIT-TABLE statement in the database that holds {@code orgId}'s home — the platform
     * database for a null org, and otherwise whichever database {@code platform.tenant_placement}
     * places that organization in (ADR 0010 §2, ADR 0011 §4.3).
     *
     * <p>This is {@link SplitTables#homeOf}'s missing half. That method answers WHICH SCHEMA and its
     * callers interpolate the name into a statement on the connection they already hold — an assumption
     * that was true while there was one database and is now true only when the row's home and the
     * caller's axis are in the same one. Both directions break otherwise: a platform operator's audit
     * row for a remote tenant names {@code t_<hex>} on primary, and a remote tenant's platform-level
     * audit row names {@code platform.audit_log} on the remote. Wrapping the statement in this makes
     * the schema name and the connection agree by construction.
     */
    public void runInHomeOf(UUID orgId, Runnable work) {
        in(orgId, () -> {
            work.run();
            return null;
        });
    }

    /** {@link #runInHomeOf} for work that returns a value. */
    public <T> T callInHomeOf(UUID orgId, Supplier<T> work) {
        return in(orgId, work);
    }

    /**
     * <strong>True when a statement addressed to {@code orgId}'s home may ride the CURRENT
     * connection</strong> — i.e. the caller's axis and that org's home are in the same database.
     *
     * <p>Exposed because two call sites have to know the answer rather than just be routed by it: a
     * queue that must decide whether its signal can still commit inside the rows' transaction
     * ({@code QueueSignals}' invariant, ADR 0011 §5), and any conversion that pays a different price on
     * each side of the boundary and should say so in a log line rather than only in behaviour.
     */
    public static boolean reachableFromHere(UUID orgId) {
        return databaseOf(TenantContext.current()).equals(databaseOfHome(orgId));
    }

    /**
     * <strong>True when this thread's axis borrows from the PLATFORM database</strong> — so a
     * platform-tier statement issued right here, on the connection already in hand, reaches the rows it
     * names and can still share a transaction with them.
     *
     * <p>Every existing deployment answers true for every thread, because every tenant is on primary.
     * The question only becomes interesting for a tenant that is not, and the one caller that must ask
     * it rather than be routed by it is {@link ug.co.smsone.shared.queue.QueueSignals#raise}: whether
     * the signal can commit inside the rows' transaction is not a routing detail, it is the difference
     * between an invariant held and an invariant traded for a sweep.
     */
    public static boolean onPlatformDatabase() {
        return TenantPlacement.PRIMARY_DATASOURCE.equals(databaseOf(TenantContext.current()));
    }

    /**
     * The hop, or not.
     *
     * <p>The comparison is between DATABASES and never between axes, and that distinction is the whole
     * class: a pooled tenant's axis is not the platform axis, but its rows and the platform's are in one
     * database, so a platform-qualified statement on its connection resolves and always has. Comparing
     * axes would hop on every request for no reason and would put a second connection and a suspended
     * transaction in front of every audit row on every existing deployment.
     */
    private <T> T in(UUID targetOrgId, Supplier<T> work) {
        if (databaseOf(TenantContext.current()).equals(databaseOfHome(targetOrgId))) {
            return work.get();
        }
        // Suspend FIRST, pin SECOND, borrow THIRD. Any other order is the pin-after-borrow no-op.
        return suspended.execute(status -> targetOrgId == null
                ? TenantContext.callAsPlatform(work)
                : TenantContext.callAs(targetOrgId, work));
    }

    /**
     * Which database an axis borrows from — the same switch {@code TenantRoutingDataSource} makes, read
     * here rather than duplicated: PLATFORM and the poison ABSENT state are primary by definition (the
     * platform tier lives nowhere else), and only an organization can route away.
     *
     * <p>A route that cannot be resolved throws out of here exactly as it throws out of a borrow. That
     * is deliberate: the alternative is assuming primary, which would send a remote tenant's write to
     * the database it was moved off — ADR 0010 §1's misroute, arrived at through a defaulting
     * comparison instead of through a defaulting router.
     */
    private static String databaseOf(Tenant axis) {
        return switch (axis) {
            case Tenant.Org org -> TenantRoutes.routeOf(org.orgId()).datasourceName();
            case Tenant.Platform ignored -> TenantPlacement.PRIMARY_DATASOURCE;
            case Tenant.Absent ignored -> TenantPlacement.PRIMARY_DATASOURCE;
        };
    }

    /** The database holding {@code orgId}'s split-table rows; primary for the org-less half. */
    private static String databaseOfHome(UUID orgId) {
        return orgId == null
                ? TenantPlacement.PRIMARY_DATASOURCE
                : TenantRoutes.routeOf(orgId).datasourceName();
    }
}
