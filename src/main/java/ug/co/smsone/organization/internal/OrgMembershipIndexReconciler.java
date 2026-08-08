package ug.co.smsone.organization.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.tenancy.TenantContext;

/**
 * The reconciler {@link OrgMembershipIndex} owes the codebase. A projection with no repair path
 * accumulates residue forever, and this repository has already proved that twice — {@code
 * SoftDeletePurgeJob.sweepSearchResidue} exists because {@code search_document} had no delete event,
 * and its {@code CASCADES} entry exists because V53 cut the {@code user_device_trust} cascade and
 * something had to take the constraint's place. ADR 0010 §8 Q2 states the resulting rule: <b>no
 * projection ships without its reconciler</b>. This is that reconciler, shipped in the same slice as
 * the projection rather than in the slice after it.
 *
 * <p><b>What actually makes them drift.</b> The write path cannot: {@link OrgMembershipIndex#record}
 * and {@link OrgMembershipIndex#forget} run in the same transaction as the {@code membership} row and
 * commit with it. The paths that do not go through it are the whole reason this class exists:
 *
 * <ul>
 *   <li>{@code SoftDeletePurgeJob} hard-deletes aged-out {@code membership} rows in raw SQL — it must,
 *       because {@code @SQLRestriction} hides those rows from JPA entirely — and raw SQL has no idea
 *       this projection exists. That leaves an index row for a membership that is gone.</li>
 *   <li>{@code SoftDeleteRecovery.restore} clears {@code deleted_at} on a membership by raw SQL too.
 *       That produces a live membership with no index row: a member who cannot see their own
 *       organization, which is the harmful direction (see {@link OrgMembershipIndex}).</li>
 *   <li>Every {@code membership} row that existed before this table did. There is no migration that
 *       could have backfilled them — the platform migration that creates this table runs before
 *       {@code tenant_pool} exists, and after Phase 5 a backfill would have to reach into every silo.
 *       So the backfill is this job's ordinary insert arm, running on its first night.</li>
 *   <li>Test fixtures that seed {@code membership} directly, which is most of them.</li>
 * </ul>
 *
 * <p><b>Per organization, and not one clever cross-schema statement.</b> While every tenant is pooled,
 * the whole fleet could be reconciled with two statements against {@code tenant_pool} — and that is
 * exactly the shape that turns catastrophic on promotion day. A promoted tenant's rows are in its own
 * schema, so a fleet-wide pass against the pool would find no memberships for it and delete <b>every
 * one of its index rows</b>, locking every member of that org out of their own switcher, silently, on
 * the night after the promotion nobody thought to connect it to. Pinning each org's own axis is
 * correct in both worlds and costs two indexed statements per org.
 *
 * <p>The one thing a per-org loop cannot see is an org that no longer exists at all, so
 * {@link #forgetOrphanedOrgs()} runs first and separately: it is platform-only (both tables are in
 * {@code platform}), needs no tenant axis, and reaches the rows the loop would never visit. It keys on
 * EXISTENCE, not liveness — a soft-deleted organization keeps its index rows, because its memberships
 * are still live underneath it and an un-delete restores a working org
 * ({@code OrganizationService#delete}). That is the same distinction {@code sweepSearchResidue} draws
 * and for the same reason.
 *
 * <p>Failure is per-org and the run still fails, which is {@code SoftDeletePurgeJob}'s doctrine
 * verbatim: <em>loud AND complete, not loud instead of complete</em>. One org whose schema is missing
 * must not cost every org after it its reconciliation.
 */
@Component
class OrgMembershipIndexReconciler {

    private static final Logger log = LoggerFactory.getLogger(OrgMembershipIndexReconciler.class);

    /**
     * Keyset page over {@code organization}. The org table is unbounded in a multi-tenant system and
     * this job runs on a scheduler thread, so heap must not scale with the tenant base — the same
     * reason {@code SystemRoleCatalogReconciler} pages rather than calling {@code findAll()}.
     */
    private static final int SCAN_PAGE = 500;

    /**
     * Re-derived, not inherited (ADR 0010 §3.4). The {@code PT30M} lease below has to cover one pass
     * over every organization, and the deadline has to stop a pass BEFORE the lease expires — an
     * overrun means a second instance acquires the lock and runs concurrently, and two reconcilers
     * racing on the same rows is how a correct insert loses to a stale delete. 25 minutes against 30
     * is the same margin {@code UsageExportJob} and {@code IdentityReconciliationJob} chose, for this
     * exact reason.
     *
     * <p>Hitting it is not a failure to recover from here: the pass is idempotent and ordered by id,
     * so tomorrow's run redoes the same work from the start. It is a signal that the fleet has
     * outgrown a single pass, and the answer then is a resumable cursor keyed on the last org
     * processed — which is Phase 4's {@code platform.tenant_placement} work, not this class's.
     */
    private static final Duration RUN_DEADLINE = Duration.ofMinutes(25);

    /** The nil UUID: lower than every real one, so the first keyset page starts at the beginning. */
    private static final UUID SCAN_START = new UUID(0L, 0L);

    /**
     * Index rows for organizations that are GONE — hard-purged, not soft-deleted. Platform-only on
     * both sides, so it needs no tenant axis and works identically before and after any promotion.
     */
    private static final String FORGET_ORPHANED_ORGS = """
            delete from platform.org_membership_index i
             where not exists (select 1 from platform.organization o where o.id = i.org_id)
            """;

    /**
     * The missing half of the disagreement: a live membership the index never learned about. Runs on
     * the org's OWN axis, so {@code membership} unqualified is that tenant's table whether it sits in
     * {@code tenant_pool} or in a silo, while the index is qualified because it is always the
     * platform's.
     *
     * <p>The {@code is distinct from} guard makes an already-correct row cost no write at all. That
     * matters more than it looks: without it, every org would rewrite every one of its index rows
     * every night, and a nightly full-table churn on a projection that almost never changes is a
     * vacuum problem invented for nothing.
     */
    private static final String INSERT_MISSING = """
            insert into platform.org_membership_index as i (person_id, org_id, status)
            select m.person_id, m.org_id, m.status from membership m
             where m.org_id = ? and m.deleted_at is null
            on conflict (person_id, org_id) do update set status = excluded.status
             where i.status is distinct from excluded.status
            """;

    /**
     * The other half: an index row whose membership is gone or soft-deleted. Scoped to this org, which
     * is what makes it safe to run while other tenants live in other schemas — see the class note on
     * why a fleet-wide version of this statement is the dangerous one.
     */
    private static final String DELETE_STALE = """
            delete from platform.org_membership_index i
             where i.org_id = ?
               and not exists (select 1 from membership m
                                where m.org_id = i.org_id and m.person_id = i.person_id
                                  and m.deleted_at is null)
            """;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    OrgMembershipIndexReconciler(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * 04:50 is chosen, not free: {@code SoftDeletePurgeJob} runs at 04:00 and is the main producer of
     * the residue this job removes, so running before it would mean every hard-deleted membership's
     * index row survived a further twenty-four hours.
     */
    @Scheduled(cron = "${app.scheduler.org-membership-index-cron:0 50 4 * * *}")
    @SchedulerLock(name = "org-membership-index-reconcile", lockAtMostFor = "PT30M")
    public void reconcile() {
        // The scan and the orphan sweep are platform-tier; the per-org repair pins each tenant in turn
        // from inside. Declared here because a scheduler thread has no axis of its own — nothing above
        // this method ever pins one, so the first borrow would land in no_tenant (ADR 0010 §3.4).
        TenantContext.runAsPlatform(this::reconcileEveryOrg);
    }

    private void reconcileEveryOrg() {
        Instant deadline = clock.instant().plus(RUN_DEADLINE);
        int orphaned = forgetOrphanedOrgs();
        int inserted = 0;
        int deleted = 0;
        int organizations = 0;
        RuntimeException firstFailure = null;
        UUID cursor = SCAN_START;
        List<UUID> page;
        while (!(page = nextPage(cursor)).isEmpty()) {
            for (UUID orgId : page) {
                if (clock.instant().isAfter(deadline)) {
                    log.warn("Membership-index reconciliation stopped at its {} deadline after {} "
                                    + "organizations; the remainder is retried on the next run",
                            RUN_DEADLINE, organizations);
                    // Not an exception: an incomplete idempotent pass is a smaller problem than a
                    // failing job nobody can distinguish from a broken one.
                    return;
                }
                organizations++;
                try {
                    int[] repaired = reconcileOrg(orgId);
                    inserted += repaired[0];
                    deleted += repaired[1];
                } catch (RuntimeException ex) {
                    // Isolated per organization: tenants are independent, so one unreachable schema
                    // must not cost every org after it its reconciliation. Kept and rethrown below so
                    // the run still fails.
                    log.error("Membership-index reconciliation failed for org {} (continuing)", orgId, ex);
                    if (firstFailure == null) {
                        firstFailure = ex;
                    }
                }
            }
            cursor = page.getLast();
        }

        if (inserted > 0 || deleted > 0 || orphaned > 0) {
            // Loud when it repairs anything. Every row this job writes is a row the write path should
            // have written and did not, so a quiet count here would hide a bug in the write path
            // behind a job that keeps cleaning up after it.
            log.warn("Membership index reconciled across {} organizations: {} routing rows added, {} "
                            + "removed, {} dropped for organizations that no longer exist",
                    organizations, inserted, deleted, orphaned);
        } else {
            log.debug("Membership index agreed with every membership across {} organizations", organizations);
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    /** @see #FORGET_ORPHANED_ORGS */
    private int forgetOrphanedOrgs() {
        return jdbc.update(FORGET_ORPHANED_ORGS);
    }

    /**
     * One keyset page of organization ids, INCLUDING soft-deleted ones. Raw SQL rather than the
     * repository precisely for that: {@code @SQLRestriction} would hide a soft-deleted organization,
     * whose memberships are still live underneath it and still need their routing rows kept honest.
     * Ordered by id — an arbitrary but total order, which is all a keyset scan needs, and stable
     * enough that a deadline-cut run redoes the same prefix rather than a different sample.
     */
    private List<UUID> nextPage(UUID after) {
        // `deleted_at is null`: a soft-deleted organization is RESTORABLE, and reconciling its index
        // against its own soft-deleted memberships would strip exactly the rows an un-delete needs —
        // DELETE_STALE keys on membership LIVENESS, and deleting an org soft-deletes its memberships
        // with it. Skipping it here is what makes the class javadoc's rule true rather than aspirational:
        // existence is the orphan sweep's question (and it reads platform.organization raw, so it sees
        // soft-deleted rows and correctly leaves them alone); liveness is the switcher's, at render time,
        // through @SQLRestriction. One authority per fact.
        return jdbc.query("select id from platform.organization where id > ? and deleted_at is null order by id limit " + SCAN_PAGE,
                (resultSet, row) -> resultSet.getObject("id", UUID.class), after);
    }

    /**
     * One organization's repair, on its own axis. Package-private so the test can prove both
     * directions without paging the whole shared container, and so the Phase 5 promotion runbook has a
     * named call for "reconcile the tenant I just moved" instead of waiting for 04:50.
     *
     * <p>{@code callAs} and not a bare {@code set}: it restores the caller's axis afterwards, which
     * the scan loop depends on — the next keyset page reads {@code platform.organization}, and a loop
     * that left the thread pinned to the last tenant would page from inside a tenant schema. The
     * qualified name would still resolve; what would not survive is the next thing someone adds to
     * that loop.
     *
     * @return rows added, then rows removed
     */
    int[] reconcileOrg(UUID orgId) {
        return TenantContext.callAs(orgId, () -> new int[] {
                jdbc.update(INSERT_MISSING, orgId),
                jdbc.update(DELETE_STALE, orgId)});
    }
}
