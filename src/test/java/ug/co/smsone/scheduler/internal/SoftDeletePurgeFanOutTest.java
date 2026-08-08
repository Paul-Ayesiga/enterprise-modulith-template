package ug.co.smsone.scheduler.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;
import ug.co.smsone.testsupport.TenantSilos;

/**
 * <b>Phase 5's gate for the purge: with one organization in its own schema and the rest pooled, both
 * get their retention</b> (ADR 0010 §5.3, §7 Phase 5).
 *
 * <p>The sibling class {@code SoftDeletePurgeJobIntegrationTest} owns everything the purge does within
 * one schema — the tier declarations, the order, the guards, the cascades, the deadline cursor — and it
 * proves all of it against {@code tenant_pool}. Every one of those assertions passed on the day a
 * promoted tenant's tombstones stopped being deleted, because there was no second schema for them to be
 * wrong about. This class is the one that can tell.
 *
 * <p><b>Why the failure it guards is the worst shape available.</b> A pooled-only purge visiting a fleet
 * with a silo in it does not error and does not log: it deletes the pool's aged rows, reports a healthy
 * count, and leaves the promoted tenant's deleted rows in place forever. "Deleted" quietly stops meaning
 * erased for exactly the customers important enough to have been given their own schema, and the only
 * signal is a GDPR request that comes back with data in it.
 *
 * <p><b>Every test here seeds and reads on the tenant's own axis, which is only meaningful because the
 * router is real.</b> {@code TenantContext.runAs(orgId, …)} resolves through
 * {@code platform.tenant_placement}, so for the placed org it lands in {@code t_<hex>} — but if that
 * resolution were ever removed, both the write and the read would silently go to {@code tenant_pool}
 * and every assertion below would still pass, with the silo empty the whole time. That is why each test
 * calls {@link TenantSilos#assertRowIsPhysicallyInTheSilo} first: it asks the question
 * schema-qualified, where no {@code search_path} can answer it for us.
 */
class SoftDeletePurgeFanOutTest extends AbstractIntegrationTest {

    /** Comfortably past the shipped P30D window; the purge runs against the real default. */
    private static final Instant AGED = Instant.now().minus(Duration.ofDays(60));

    @RegisterExtension
    final TenantSilos silos = new TenantSilos();

    @Autowired
    private SoftDeletePurgeJob job;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void purgesAgedRowsInASiloAndInThePoolInTheSameRun() {
        UUID pooled = organization();
        UUID siloed = organization();
        // place() BEFORE the tenant-tier rows: it copies nothing, so an org seeded first would leave its
        // rows stranded in the schema it no longer reads.
        silos.place(siloed);

        UUID pooledRole = insertAgedRole(pooled);
        UUID siloedRole = insertAgedRole(siloed);
        // Before the job runs, and schema-qualified so no search_path decides the answer: the siloed
        // row is really in t_<hex> and really not in the pool. Without this the two assertions below
        // pass identically with the fan-out reverted — both the write and the read would go to
        // tenant_pool, the pooled visit would purge the row, and the silo would be empty from creation
        // to drop. See TenantSilos.assertRowIsPhysicallyInTheSilo.
        TenantSilos.assertRowIsPhysicallyInTheSilo(jdbc, siloed, "org_role", "id", siloedRole);

        runPurge();

        assertThat(exists(pooled, pooledRole))
                .as("the pooled tenant's tombstone is purged, as it always was").isFalse();
        assertThat(exists(siloed, siloedRole))
                .as("and so is the siloed tenant's. Before the fan-out this row survived every night "
                        + "forever, with the job logging a healthy count and raising nothing — erasure "
                        + "silently stopped being an erasure guarantee for promoted tenants")
                .isFalse();
    }

    /**
     * A row inside the retention window must survive in a silo exactly as it does in the pool. Without
     * this, a fan-out that reached the silo but got its cutoff wrong — the opposite mistake, and the
     * unrecoverable one — would pass the test above.
     */
    @Test
    void aFreshTombstoneInASiloIsLeftAloneJustAsItIsInThePool() {
        UUID siloed = organization();
        silos.place(siloed);
        UUID fresh = insertRole(siloed, Instant.now());
        TenantSilos.assertRowIsPhysicallyInTheSilo(jdbc, siloed, "org_role", "id", fresh);

        runPurge();

        assertThat(exists(siloed, fresh))
                .as("retention is a window, not a sweep: a row deleted a minute ago is still restorable")
                .isTrue();
        TenantContext.runAs(siloed, () -> jdbc.update("delete from org_role where id = ?", fresh));
    }

    /**
     * <b>The Phase 5 gate sentence: "SoftDeletePurgeJob completes within its lease with a silo
     * present."</b>
     *
     * <p>Asserted as wall-clock time against the {@code PT30M} lease rather than by reading a log line,
     * because the failure being guarded is a real one: fanning out multiplies the run by the home count,
     * and a run that outlives its lease is not a slow job — ShedLock hands the lock to a second replica
     * while the first is still deleting, and two purges then interleave batches of the same table with
     * the {@code org_role} FK guard evaluated against rows the other instance is removing.
     *
     * <p>The margin asserted is deliberately gross (one minute against thirty). A tighter bound would be
     * measuring this laptop rather than the property; what would actually be alarming is a run that took
     * minutes with two nearly-empty homes, and that is what this catches.
     */
    @Test
    void aRunWithASiloPresentFinishesWellInsideItsLease() {
        UUID siloed = organization();
        silos.place(siloed);
        UUID aged = insertAgedRole(siloed);
        TenantSilos.assertRowIsPhysicallyInTheSilo(jdbc, siloed, "org_role", "id", aged);

        Instant started = Instant.now();
        runPurge();
        Duration elapsed = Duration.between(started, Instant.now());

        assertThat(elapsed)
                .as("a run over two homes must finish nowhere near the PT30M lease; if this ever fails, "
                        + "measure a home and re-derive RUN_DEADLINE and the lease together rather than "
                        + "raising the lease, which moves the overrun instead of removing it")
                .isLessThan(Duration.ofMinutes(1));
    }

    /**
     * ShedLock's advisor intercepts direct calls too, and {@code lockAtLeastFor} holds the lock after a
     * run — so a second test would be silently skipped and pass for the wrong reason. Expiring the row
     * is what the provider's acquire actually looks at; deleting it strands its record cache.
     */
    private void runPurge() {
        jdbc.update("update platform.shedlock set lock_until = timestamp '1970-01-01 00:00:00' where name = ?",
                "soft-delete-purge");
        job.purgeExpiredSoftDeletes();
    }

    private UUID organization() {
        return EdgeSeed.organization(jdbc, "kc-" + UUID.randomUUID(), "fanout-" + UUID.randomUUID());
    }

    private UUID insertAgedRole(UUID orgId) {
        return insertRole(orgId, AGED);
    }

    /**
     * A soft-deleted {@code org_role}: tenant-tier, soft-deletable, near the head of
     * {@code PURGE_ORDER}, and — the reason it is the probe rather than {@code org_group} — referenced
     * by nothing this test creates, so {@code GUARDS}' membership anti-join cannot hold it back and the
     * assertion stays about the fan-out rather than about a second mechanism.
     *
     * <p>Written on the org's OWN axis. That is the whole point of the fixture: for the siloed org this
     * insert lands in {@code t_<hex>}, and a job that could not follow it there simply leaves the row
     * where it is.
     */
    private UUID insertRole(UUID orgId, Instant deletedAt) {
        UUID id = UUID.randomUUID();
        TenantContext.runAs(orgId, () -> jdbc.update("""
                insert into org_role (id, org_id, code, name, system_role, version, created_at, deleted_at)
                values (?, ?, ?, ?, false, 0, now(), ?)
                """, id, orgId, "FANOUT_" + id.toString().substring(0, 8).toUpperCase(), "Fan-out probe",
                Timestamp.from(deletedAt)));
        return id;
    }

    private boolean exists(UUID orgId, UUID id) {
        return Boolean.TRUE.equals(TenantContext.callAs(orgId, () -> jdbc.queryForObject(
                // Raw SQL and no deleted_at predicate: @SQLRestriction hides exactly the rows under
                // test, which is why the whole purge speaks SQL rather than JPA.
                "select exists(select 1 from org_role where id = ?)", Boolean.class, id)));
    }
}
