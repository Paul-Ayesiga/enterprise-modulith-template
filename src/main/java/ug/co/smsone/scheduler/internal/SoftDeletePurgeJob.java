package ug.co.smsone.scheduler.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.persistence.MappedTables;
import ug.co.smsone.shared.persistence.SoftDeleteProperties;
import ug.co.smsone.shared.tenancy.JobAxis;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.shared.tenancy.TenantFanOut;
import ug.co.smsone.shared.tenancy.TenantHomeSweep;
import ug.co.smsone.shared.tenancy.TenantSchemas;

/**
 * Retention for soft-deleted aggregates: once {@code deleted_at} falls outside the window the row is
 * hard deleted, which is what turns "deleted" from a hidden state into an erasure guarantee.
 *
 * <p>Native SQL is not a shortcut here, it is the only option: {@code @SQLRestriction("deleted_at is
 * null")} makes these rows invisible to every HQL/criteria query, so JPA cannot see the very rows this
 * job exists to remove.
 *
 * <p>A failure on one table is logged and the run continues, then the run still fails. Unlike the other
 * purge jobs, aborting here is not free: the tables are ordered, so a table that fails every night
 * starves every table after it — and a foreign-key violation on {@code org_role} IS permanent, because
 * the offending row does not go away on its own. Erasure would quietly stop platform-wide while the
 * only symptom is one stack trace at 04:00. Loud AND complete, not loud instead of complete.
 *
 * <p><b>It runs in two passes, one per tenancy tier, and the tenant pass runs once per HOME</b> — see
 * {@link #purgeExpiredSoftDeletes} for why the tier boundary is an axis boundary and not something a
 * wider pin can paper over, {@link Tier} for why every entry in {@link #PURGE_ORDER} names its own, and
 * {@link ug.co.smsone.shared.tenancy.TenantFanOut} for why "every tenant" stopped being one
 * schema the day the first organization was promoted. Before that loop existed, a promoted tenant's
 * tombstones were never hard-deleted and the run reported success — erasure silently stopped meaning
 * erasure for exactly the customers important enough to be given their own schema.
 *
 * <p>Two other things run in the same run and are not retention at all: {@link #sweepSearchResidue}
 * un-indexes rows whose source is gone, and {@link #CASCADES} deletes children a cut foreign key no
 * longer removes. Both are reconcilers — they compare two tables and fix the disagreement — and both
 * exist because a projection or a soft ref with no delete path leaves residue forever.
 */
@Component
class SoftDeletePurgeJob {

    private static final Logger log = LoggerFactory.getLogger(SoftDeletePurgeJob.class);

    /**
     * How long a whole run may take before it stops itself, against the {@code PT30M} lease on
     * {@link #purgeExpiredSoftDeletes}. Twenty-five is the same five-minute margin
     * {@code IdentityReconciliationJob}, {@code UsageExportJob} and {@code OrgMembershipIndexReconciler}
     * chose, and it is here for a reason this job did not previously acknowledge at all: <b>it had no
     * deadline.</b> 24 tables × {@code max-batches} (100) × {@code batch-size} (500) is 1.2 million
     * deletes, every one of them behind a {@code legal_hold} anti-join, and nothing stopped the pass
     * before the lease expired. A lease that expires under a running pass is not a slow job — it lets a
     * second replica acquire the lock and start purging the same tables concurrently, which is how two
     * batches of the same table interleave and how the {@code org_role} FK guard gets evaluated against
     * a {@code membership} row another instance is in the middle of deleting.
     *
     * <p>Whether five minutes is enough margin is <b>a judgement, not a measurement</b>: it has to cover
     * the last in-flight batch plus the cascade sweeps plus {@link #sweepSearchResidue}, and the only
     * one of those with a measured number is the residue sweep (212 ms as a Hash Anti Join at 200k
     * people). Batch cost is the unmeasured term. If a production run ever logs the deadline warning,
     * the honest response is to measure a batch and re-derive both numbers together — not to raise the
     * lease, which moves the overrun rather than removing it.
     */
    private static final Duration RUN_DEADLINE = Duration.ofMinutes(25);

    /**
     * The share of {@link #RUN_DEADLINE} the PLATFORM pass may spend, so it cannot starve the tenant
     * pass that runs after it.
     *
     * <p>The two passes are not symmetrical and that asymmetry is the whole derivation. The platform
     * pass visits 14 tables in ONE schema and will still visit 14 tables in one schema after Phase 7 —
     * {@code platform} never fans out. The tenant pass visits 13 in (silos + 1) schemas, so it is the
     * only half whose cost grows with the fleet. A single shared deadline would therefore let the
     * fixed-size half consume a budget the growing half needs, and the failure would be silent: the
     * tenant cursor would sit at index 0 forever while the log said the run completed.
     *
     * <p>A third of the budget for the smaller, fixed half is deliberately generous and equally
     * unmeasured. It is not a ratio to defend — it is a floor under the tenant pass, and the property
     * that matters is that one exists at all. An early-finishing platform pass hands its unspent time
     * straight to the tenant pass, because the tenant deadline is computed from the run's start and not
     * from where the platform pass stopped.
     *
     * <p><b>Phase 5 sharpened this rather than changing it.</b> The tenant pass is now (silos + 1)
     * schemas, so what this budget protects is split AGAIN by {@link TenantHomeSweep} — half to
     * {@code tenant_pool}, the rest shared by the silos. Two nested floors, each holding a half whose
     * cost does not grow away from a half whose cost does.
     */
    private static final Duration PLATFORM_BUDGET = RUN_DEADLINE.dividedBy(3);

    /**
     * Which home this job is sweeping and where the rotation resumes — one instance, held here because
     * the position is this job's and has to outlive a run (ADR 0010 §3.4). Only the TENANT pass uses
     * it: {@code platform} is one schema and never fans out.
     */
    private final TenantHomeSweep tenantHomes = new TenantHomeSweep("Soft-delete purge");

    /**
     * Where each pass resumes: the index into {@link #PURGE_ORDER} the last run was cut at, or absent
     * when that pass ran to the end. <b>Keyed by (tier, SCHEMA) since Phase 5</b> — a cursor that says
     * "table 12" is meaningless once "which home" is a second axis, and one cursor shared across homes
     * would let a silo cut at {@code ticket} resume the pool at {@code ticket} too, skipping every table
     * ahead of it in the schema that holds every unpromoted tenant.
     *
     * <p><b>This is the resumable cursor ADR 0010 §3.4 asks every sweeping job for</b> — the TABLE half
     * of it; {@link #tenantHomes} holds the HOME half, and the two are independent on purpose. Without
     * it the deadline above would have made this job worse rather than better: a pass that
     * always restarts at index 0 re-purges {@code org_group}, {@code membership} and {@code org_role}
     * every night and never reaches {@code ticket} or {@code geo_stamp}, so the tables at the tail of
     * the list would quietly keep their tombstones forever while the job logged a healthy row count from
     * the head.
     *
     * <p><b>It is IN MEMORY, and here is exactly what that buys and what it does not.</b> It survives
     * across nights within one process, which is the case that matters — a pod lives for days and a
     * fleet too large for one pass drains over consecutive runs. It does NOT survive a restart (the
     * next run starts at the head, which is the safe direction: every step is idempotent, so the cost
     * of re-doing the head is time, never correctness), and it is NOT shared between replicas — each
     * keeps its own, so when the ShedLock winner alternates the two advance independently and overlap.
     * Overlap is waste; starvation is data loss. The durable, shared version of this cursor belongs in
     * a {@code platform} table alongside ADR 0010 Phase 4's {@code tenant_placement}, where the
     * per-schema position a Phase 5 loop needs will live anyway — writing it now would mean a migration
     * this slice does not own.
     *
     * <p><b>Why resuming mid-list cannot violate {@link #PURGE_ORDER}'s one real constraint.</b> The
     * order exists for {@code membership.role_id → org_role(id)}, and a resumed pass may reach
     * {@code org_role} without having visited {@code membership} first that night. It is safe because
     * {@link #GUARDS} excludes any {@code org_role} still referenced by ANY {@code membership} row —
     * the anti-join carries no {@code deleted_at} predicate, so soft-deleted memberships hold their
     * role back too. The order is what stops the guard having to do that work on the common path; the
     * guard is what makes the order optional on the resumed one. Both, not either.
     *
     * <p>Concurrent rather than an {@code EnumMap}, and not because two runs overlap — ShedLock is what
     * stops that. It is for VISIBILITY: consecutive nightly runs land on whichever thread the scheduler
     * pool hands out, and a plain field written by one thread and read by another a day later has no
     * happens-before edge between them. The failure would be silent and would look exactly like the bug
     * this cursor removes — a pass restarting at the head — which is the worst possible way to lose it.
     */
    private final Map<PassKey, Integer> resumeAt = new ConcurrentHashMap<>();

    /**
     * One pass: a tier in one schema. The schema is part of the key because the same tier is now walked
     * once per home, and each home drains at its own rate — a silo with four tombstones finishes every
     * night while the pool is still working through its tail.
     */
    private record PassKey(Tier tier, String schema) {
    }

    /**
     * The tenancy tier of a purge step's table, and therefore WHICH PASS visits it (ADR 0010 §2).
     *
     * <p>Written down per entry rather than derived at the call site, because the failure it prevents
     * is silent and nightly: a table visited on the wrong axis resolves to nothing, raises
     * {@code relation "…" does not exist} at 04:00, and takes its retention promise with it.
     * {@code SoftDeletePurgeJobIntegrationTest.everyPurgeStepDeclaresTheTierItsTableActuallyLivesIn}
     * checks every declaration below against the database itself, so a table added to the wrong pass
     * fails in the build rather than in the night.
     */
    enum Tier {

        /**
         * One copy, in {@code platform}. Addressed {@code platform.<table>} — so it would in fact
         * resolve from either axis — but it is purged on the platform pass, because that is where it
         * belongs and because the day the tiers become separate DATABASES (Phase 7) the pass is the
         * only thing that will still be true.
         */
        PLATFORM,

        /**
         * The tenant's own table, addressed BARE so {@code search_path} decides which schema that is.
         * Purged on the tenant pass; on the platform pass the same name resolves to nothing.
         */
        TENANT,

        /**
         * One of §2's seven SPLIT tables: a copy in each schema, both soft-deletable, both owed a
         * purge. Visited by BOTH passes under the SAME bare name — the {@code search_path} is the only
         * thing that makes the two visits different tables. Miss one pass and the other home's
         * tombstones live forever: {@code document}'s platform copy holds PERSONAL documents, and
         * "erased" would quietly stop meaning erased for every human on the platform.
         */
        BOTH;

        /** Whether the pass running on {@code passTier} visits a table of this tier. */
        boolean visitedOn(Tier passTier) {
            return this == passTier || this == BOTH;
        }
    }

    /** One table in {@link #PURGE_ORDER}, with the tier that says which pass reaches it. */
    record PurgeStep(String table, Tier tier) {
    }

    /**
     * Purge order — CHILDREN BEFORE PARENTS, and the order below is load-bearing, not cosmetic.
     *
     * <p>The only foreign key between soft-deletable tables is {@code membership.role_id -> org_role(id)}
     * (V11), and it has no {@code on delete cascade}. That FK is blind to {@code deleted_at}: a
     * soft-deleted membership still pins its role. Purging {@code org_role} first would therefore fail
     * with a constraint violation the moment a role and one of its memberships age out together — the
     * normal case, since removing the last member is usually what precedes deleting the role.
     *
     * <p>{@code role_permission} (V11) and {@code webhook_delivery} (V15) are deliberately ABSENT rather
     * than forgotten: both FKs are {@code on delete cascade}, so Postgres removes them with their parent
     * row. Adding explicit steps would be dead code.
     *
     * <p>{@code user_device_trust} (V51) was a third such table until V53, and is not one now: its
     * cascade crossed the tenant boundary and was cut, so Postgres no longer removes it with anything.
     * It is NOT in this list either — it has no {@code deleted_at} to age out, so there is nothing here
     * to purge it BY. It lives in {@link #CASCADES}, which hangs off {@code user_device} and reconciles
     * on the parent's liveness rather than on the child's age. That distinction is why the list below
     * can stay exactly what its test says it is: every soft-deletable entity, and only those.
     *
     * <p>{@code external_identity}, {@code person_contact} and {@code external_organization} DO have real
     * FKs to their parents (V10/V11, intra-module and therefore permitted) and all three cascade — yet
     * they are listed, ahead of those parents, and both facts matter. Listed, because each can be
     * soft-deleted on its OWN while the parent lives (unlinking a provider, removing an address), and a
     * cascade that never fires purges nothing. Ahead, because children-before-parents is the rule this
     * order states; following it costs nothing here, while leaning on the cascade would make the order a
     * special case per table.
     *
     * <p>The remaining tables are unordered on purpose. {@code organization}, {@code person},
     * {@code membership} and {@code webhook_subscription} reference each other by {@code organization.id}
     * and {@code person.id} held as plain columns, never as foreign keys — a cross-module reference in
     * this codebase is a soft ref, so the database imposes no order on them.
     *
     * <p>V53 moved {@code membership}, {@code org_role} and {@code org_group} into that group: their
     * {@code org_id} keys to {@code organization} were real, and cutting them means purging an
     * organization no longer FAILS while its rows are still there — it succeeds and leaves them
     * orphaned. Their position ahead of {@code organization} is kept anyway, because children-before-
     * parents is what this order states and an orphan is worth avoiding even when nothing enforces it.
     * The real answer arrives with the tenant schema, which is dropped whole (ADR 0010 §5).
     *
     * <p><b>The order is preserved WITHIN a pass and means nothing across passes, which is exactly
     * what ADR 0010 §2 guarantees</b> — no foreign key may connect two tiers, so no cross-tier
     * ordering constraint can exist for one to violate. {@code membership} still precedes
     * {@code org_role} (both tenant) and {@code external_organization} still precedes
     * {@code organization} (both platform); nothing in this list depends on a tenant table being
     * purged before a platform one, because nothing in the database could enforce it if it did.
     *
     * <p>Package-private so the integration test can check this list against every
     * {@code SoftDeletableEntity} in the metamodel: a table added to the mapping but not to this list
     * would leak deleted rows forever, and silently. Each entry carries its {@link Tier} for the same
     * class of reason — see {@link Tier}.
     */
    static final List<PurgeStep> PURGE_ORDER = List.of(
            new PurgeStep("org_group", Tier.TENANT),
            new PurgeStep("membership", Tier.TENANT),
            new PurgeStep("org_role", Tier.TENANT),
            new PurgeStep("external_organization", Tier.PLATFORM),
            new PurgeStep("organization", Tier.PLATFORM),
            new PurgeStep("external_identity", Tier.PLATFORM),
            new PurgeStep("person_contact", Tier.PLATFORM),
            new PurgeStep("person", Tier.PLATFORM),
            new PurgeStep("webhook_subscription", Tier.TENANT),
            new PurgeStep("setting", Tier.PLATFORM),
            new PurgeStep("feature_flag", Tier.PLATFORM),
            new PurgeStep("translation", Tier.PLATFORM),
            new PurgeStep("document", Tier.BOTH),
            new PurgeStep("exchange_schedule", Tier.TENANT),
            new PurgeStep("org_subscription", Tier.TENANT),
            new PurgeStep("billing_account", Tier.TENANT),
            new PurgeStep("person_profile", Tier.PLATFORM),
            new PurgeStep("api_key", Tier.PLATFORM),
            new PurgeStep("user_device", Tier.PLATFORM),
            new PurgeStep("org_security_policy", Tier.TENANT),
            new PurgeStep("integration", Tier.BOTH),
            new PurgeStep("maintenance_window", Tier.BOTH),
            new PurgeStep("ticket", Tier.TENANT),
            new PurgeStep("geo_stamp", Tier.TENANT));

    /** The bare table names {@link #PURGE_ORDER} visits, for the tests that pin it to the metamodel. */
    static List<String> purgeOrderTables() {
        return PURGE_ORDER.stream().map(PurgeStep::table).toList();
    }

    /**
     * One reconciliation statement plus the table it empties — the child's name is what gets reported —
     * and the tier of THAT CHILD, which is the axis the statement has to run on. It is not necessarily
     * the parent's: {@code user_device} is platform-tier and {@code user_device_trust} is the tenant's,
     * which is precisely why the FK between them had to be cut (ADR 0010 §6) and why this reconciler
     * exists at all.
     */
    private record Cascade(String childTable, Tier tier, String sql) {
    }

    /**
     * Child rows a foreign key used to remove and no longer does, because the FK crossed the TENANT
     * boundary and ADR 0010 §6 cut it. Keyed by the {@link #PURGE_ORDER} table whose rows they hang
     * off, so a cascade can never be declared for a table this job does not actually visit —
     * {@code SoftDeletePurgeJobIntegrationTest.everyCascadeHangsOffATablePurgeOrderVisits} asserts it.
     *
     * <p><b>This is a RECONCILER, not a delete-my-children step, and the difference is the whole
     * point.</b> The anti-join asks whether the parent is LIVE, not whether it still exists. That makes
     * it reach a case no cascade ever could: a device that was soft-deleted — revoked — but whose
     * hard delete is thirty days away. {@code user_device_trust} has no {@code deleted_at} of its own
     * and, since V53, no join to the device's, so a grant whose device was revoked by a path that
     * published no {@code DeviceRevoked} event would otherwise keep satisfying
     * {@code require_trusted_device} until retention caught up. There is such a path today:
     * {@code ComplianceService}'s erasure soft-deletes {@code user_device} rows by raw SQL. This is what
     * closes it, and it closes the next one too — ADR 0010 §8 Q2's rule is that no projection ships
     * without its reconciler.
     *
     * <p>Order-independent by construction: it keys on liveness, so running it before or after the
     * parent's own batches gives the same answer. It runs after — at the end of the pass its own tier
     * names, which for this one is the TENANT pass, after the platform pass has already hard-deleted
     * whatever it was going to. That ordering is why the platform pass runs first (see
     * {@link #purgeEverything}) and why one night's run still sees the final state.
     *
     * <p>Table names here are constants in this class, never input — the same rule the interpolated
     * {@link #DELETE_BATCH} follows, and the reason these statements are literals rather than built.
     * {@code user_device_trust} is bare because it is the tenant's and the pass's {@code search_path}
     * places it; {@code platform.user_device} is named because it is not, and a cross-tier anti-join
     * stays expressible only until Phase 7 makes the two tiers separate databases.
     */
    private static final Map<String, Cascade> CASCADES = Map.of(
            "user_device", new Cascade("user_device_trust", Tier.TENANT, """
                    delete from user_device_trust t
                    where not exists (
                        select 1 from platform.user_device d
                         where d.id = t.device_id and d.deleted_at is null)
                    """));

    /** Package-private for the test that checks every cascade hangs off a table {@link #PURGE_ORDER} visits. */
    static java.util.Set<String> cascadeParents() {
        return CASCADES.keySet();
    }

    /**
     * Bounded batch: the inner select is what the {@code idx_<table>_deleted} partial indexes (V17) were
     * created for, and oldest-first keeps successive batches deterministic. The table name and the
     * guard are interpolated from constants in this class — never from input.
     */
    private static final String DELETE_BATCH = """
            delete from %1$s where id in (
                select id from %1$s where deleted_at < ?%2$s order by deleted_at limit ?)
            """;

    /**
     * Extra predicates that skip rows a purge could not delete anyway. The order above resolves the
     * <em>expected</em> collision (a role and its memberships aging out together); this one covers the
     * pathological state the order cannot help with — a LIVE membership pinning a deleted role, which
     * {@code SoftDeleteRecovery.restore} can produce by restoring a membership whose role is still
     * deleted. There is no ordering that clears it and the row does not age out, so without this the
     * same FK violation fails the same table every single night.
     */
    private static final Map<String, String> GUARDS = Map.of(
            "org_role", " and not exists (select 1 from membership m where m.role_id = org_role.id)");

    /**
     * LEGAL HOLDS block erasure. A row whose owner (a person or an org) is under an active hold is
     * NOT hard-deleted, however old its {@code deleted_at} — the hold outranks retention. Keyed by
     * the table's owner column; a global table (setting/feature_flag/translation) has no owner and
     * no hold applies. Table and column are constants from this class — never input. The scheduler
     * reads {@code legal_hold} by raw SQL, the same cross-module reach the purge already has over
     * every module's tables (the sanctioned purge exception, AGENTS §7).
     *
     * <p><b>Both sides of every comparison below moved</b>, and V34 flags this as the mismatch that does
     * not throw: it matches nothing, and a nightly job quietly resumes hard-deleting data a court said
     * to keep. The hold columns are {@code legal_hold.person_id} and {@code legal_hold.org_id}, both
     * uuid; the owner columns are {@code person.id} / {@code person_id} and {@code organization.id} /
     * {@code org_id}. The old code compared a Keycloak subject against a {@code subject} column and an
     * {@code org_id} against {@code organization.kc_org_id} — a column that no longer exists at all.
     */
    private enum Owner {
        /**
         * The owner is a person. Names the {@code legal_hold.person_id} column — not
         * {@code legal_hold.scope}, whose stored vocabulary still spells this {@code SUBJECT} (V34)
         * because rows already say so. Two different things, one of which is data.
         */
        PERSON,
        ORG
    }

    private static final Map<String, Map.Entry<Owner, String>> HELD_OWNER = Map.ofEntries(
            // person is held by its OWN key; everything else soft-references it.
            Map.entry("person", Map.entry(Owner.PERSON, "id")),
            Map.entry("external_identity", Map.entry(Owner.PERSON, "person_id")),
            Map.entry("person_contact", Map.entry(Owner.PERSON, "person_id")),
            Map.entry("person_profile", Map.entry(Owner.PERSON, "person_id")),
            Map.entry("user_device", Map.entry(Owner.PERSON, "person_id")),
            // Same shape one level up: the tenant row is held by its own key, its rows by org_id.
            Map.entry("organization", Map.entry(Owner.ORG, "id")),
            Map.entry("external_organization", Map.entry(Owner.ORG, "organization_id")),
            Map.entry("membership", Map.entry(Owner.ORG, "org_id")),
            Map.entry("org_role", Map.entry(Owner.ORG, "org_id")),
            Map.entry("org_group", Map.entry(Owner.ORG, "org_id")),
            Map.entry("webhook_subscription", Map.entry(Owner.ORG, "org_id")),
            Map.entry("document", Map.entry(Owner.ORG, "org_id")),
            Map.entry("exchange_schedule", Map.entry(Owner.ORG, "org_id")),
            Map.entry("org_subscription", Map.entry(Owner.ORG, "org_id")),
            Map.entry("billing_account", Map.entry(Owner.ORG, "org_id")),
            Map.entry("api_key", Map.entry(Owner.ORG, "org_id")),
            Map.entry("org_security_policy", Map.entry(Owner.ORG, "org_id")),
            Map.entry("integration", Map.entry(Owner.ORG, "org_id")),
            Map.entry("geo_stamp", Map.entry(Owner.ORG, "org_id")));

    private final JdbcTemplate jdbc;
    private final SoftDeleteProperties properties;
    private final Clock clock;
    private final io.micrometer.core.instrument.MeterRegistry meters;
    private final MappedTables tables;
    private final TenantFanOut fanOut;

    SoftDeletePurgeJob(JdbcTemplate jdbc, SoftDeleteProperties properties, Clock clock,
            io.micrometer.core.instrument.MeterRegistry meters, MappedTables tables, TenantFanOut fanOut) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
        this.meters = meters;
        this.tables = tables;
        this.fanOut = fanOut;
    }

    /**
     * The active-hold exclusion for a table, or empty when the table has no held owner column.
     *
     * <p>{@code legal_hold} is named {@code platform.legal_hold} and the owner column is qualified off
     * whatever name {@link #purgeTable} resolved (ADR 0010 §2). Holds are platform-tier because there
     * must be exactly ONE set of them: a per-tenant copy this job could not see is a hold that
     * silently stops holding, which is the failure the table exists to prevent — and an unqualified
     * name on a tenant-pinned connection is that copy in every way that matters, because it resolves
     * to nothing and the {@code not exists} then excludes nothing.
     */
    private static String heldGuard(String qualifiedTable) {
        Map.Entry<Owner, String> owner = HELD_OWNER.get(bareName(qualifiedTable));
        if (owner == null) {
            return "";
        }
        String column = owner.getValue();
        String holdColumn = owner.getKey() == Owner.PERSON ? "person_id" : "org_id";
        return " and not exists (select 1 from platform.legal_hold h where h.released_at is null and h."
                + holdColumn + " = " + qualifiedTable + "." + column + ")";
    }

    /** {@code platform.person} → {@code person}: the key {@link #HELD_OWNER} and {@link #GUARDS} use. */
    private static String bareName(String qualifiedTable) {
        int dot = qualifiedTable.indexOf('.');
        return dot < 0 ? qualifiedTable : qualifiedTable.substring(dot + 1);
    }

    /**
     * <b>{@link #PURGE_ORDER} spans both tiers, so this is TWO passes on two axes — not one wider
     * pin.</b> ADR 0010 Phase 2 moved the tables apart: the eleven platform-tier names are written
     * {@code platform.<table>} (as are {@code legal_hold} and {@code search_document}), the ten
     * tenant-tier ones stay bare so {@code search_path} places them, and the three split ones are bare
     * in BOTH schemas — same name, two tables. The name is resolved per table in {@link #purgeTable},
     * off the entity mapping; the AXIS is resolved here, off the {@link Tier} each step declares.
     *
     * <p>One pin could not do it. {@code runAsPlatform} puts {@code search_path} on {@code platform,
     * ext}, where {@code org_group}, {@code membership}, {@code org_role}, {@code webhook_subscription},
     * {@code exchange_schedule}, {@code org_subscription}, {@code billing_account},
     * {@code org_security_policy}, {@code ticket}, {@code geo_stamp} and {@code user_device_trust}
     * resolve to nothing — which is what a platform-pinned Phase 1 purge was quietly doing to every
     * tenant's retention. A single tenant-pinned pass would reach the platform tables too (they are
     * named, and a named schema ignores {@code search_path}) but it would purge the wrong half of the
     * three split tables and it would stop working the day Phase 7 puts the tiers in separate
     * databases. Two passes are what the tier boundary actually is.
     *
     * <p><b>Platform first, then tenant, and the reason is {@link #CASCADES}.</b> Nothing else orders
     * them — no foreign key may cross a tier (§2), so neither pass can violate the other's
     * constraints. The one real dependency is the {@code user_device_trust} reconciler: it hangs off
     * {@code platform.user_device}'s LIVENESS, so running it after the platform pass has finished its
     * hard deletes lets a single night's run see the final state instead of trailing it by a day.
     *
     * <p>Not one giant transaction, and not one connection: every batch commits on its own, and every
     * one of those borrows reads the axis afresh — which is exactly why the pins wrap the passes
     * rather than sitting inside them (ADR 0010 §3.4).
     *
     * <p><b>Since Phase 5 the tenant pass is a LOOP over homes, not one pass over the pool.</b>
     * {@code tenant_pool} plus every ACTIVE silo, each on its own axis, each in its own transactions —
     * see {@link TenantFanOut} for why "one statement per home" and not "one UNION over the homes" is
     * what BOUNDED means. A promoted tenant is not a tenant with less retention; before this loop
     * existed it was a tenant with NO retention, and nothing said so.
     *
     * <p><b>AXIS: PLATFORM and TENANT, two spans, for the reason spelled out above</b> — this is the
     * job the {@link JobAxis} annotation's two-valued form exists for; the tenant span is now opened
     * once per home by {@link TenantHomeSweep}, which is a change to how many times the axis is
     * declared and not to which axis it is. <b>CURSOR:</b> two of them, and they are independent —
     * {@link #resumeAt} for the position in {@link #PURGE_ORDER} within one (tier, home), and
     * {@link #tenantHomes} for which home the rotation resumes at. <b>LEASE:</b> {@code PT30M} against
     * a {@link #RUN_DEADLINE} of 25 minutes, split by {@link #PLATFORM_BUDGET} and then again per home;
     * every javadoc says what its number is sized for and which of them is a guess.
     */
    @Scheduled(cron = "${app.scheduler.soft-delete-purge-cron:0 0 4 * * *}")
    @SchedulerLock(name = "soft-delete-purge", lockAtMostFor = "PT30M")
    // Written out rather than static-imported, uniquely in this file: `Tier` below declares its OWN
    // PLATFORM and TENANT constants, and two vocabularies sharing two simple names in one compilation
    // unit is a thing to read twice. They are not the same enum and they answer different questions —
    // Tier is which SCHEMA a table lives in, Axis is which span this job opens.
    @JobAxis({JobAxis.Axis.PLATFORM, JobAxis.Axis.TENANT})
    public void purgeExpiredSoftDeletes() {
        purgeEverything();
    }

    private void purgeEverything() {
        if (!properties.purgeEnabled()) {
            log.debug("Soft-delete purge is disabled (app.persistence.soft-delete.purge-enabled)");
            return;
        }
        Instant started = clock.instant();
        Instant cutoff = started.minus(properties.retention());
        // Both deadlines are absolute and both are computed from the run's start, which is what lets an
        // early-finishing platform pass hand its unspent budget to the tenant pass rather than have it
        // expire unused. See PLATFORM_BUDGET.
        Instant platformDeadline = started.plus(PLATFORM_BUDGET);
        Instant runDeadline = started.plus(RUN_DEADLINE);

        // Read BEFORE the platform pass, not between the passes: one snapshot per run is what stops the
        // tenant loop disagreeing with itself about how many homes there are half way through, and a
        // registry read is cheap enough that taking it early costs nothing.
        TenantFanOut.Fleet fleet = fanOut.fleet();

        // Not @Transactional on purpose: every batch commits on its own connection, so a long backlog
        // never becomes one giant transaction holding row locks against live traffic.
        Run run = new Run();
        TenantContext.runAsPlatform(() -> pass(Tier.PLATFORM, TenantSchemas.PLATFORM, cutoff,
                platformDeadline, run));
        // One tenant pass per HOME. The sweep pins each home's axis, gives it a slice of what is left of
        // the run's budget, isolates its failures and remembers where it stopped — see TenantHomeSweep.
        TenantHomeSweep.Swept swept = tenantHomes.over(fleet.homes(), clock, runDeadline,
                (home, homeDeadline) -> pass(Tier.TENANT, home.schema(), cutoff, homeDeadline, run));
        if (swept.cutShort()) {
            run.cutShort = true;
        }
        if (swept.firstFailure() != null) {
            // A home that could not be reached at all — pass() catches everything a TABLE can throw, so
            // what arrives here is the connection or the schema, and it must still fail the run.
            run.failed(swept.firstFailure());
        }

        if (run.total > 0) {
            log.info("Soft-delete purge removed {} rows deleted before {} (retention {}): {}",
                    run.total, cutoff, properties.retention(), run.purged);
        } else if (run.firstFailure == null && !run.cutShort) {
            log.debug("Soft-delete purge found nothing deleted before {}", cutoff);
        }
        // Three platform-tier tables, all named — but the pin still has to be declared, because this
        // runs after both passes have restored whatever axis the caller had.
        TenantContext.runAsPlatform(this::sweepSearchResidue);
        if (run.firstFailure != null) {
            throw run.firstFailure;
        }
    }

    /**
     * One night's tally, carried across both passes: the log line, the total and the failure that
     * fails the run are the RUN's, not a pass's. A split table appears once in the map with the two
     * homes' counts summed — {@code document: 7} is seven document rows gone from this installation,
     * which is the fact retention promises.
     */
    private static final class Run {
        private final Map<String, Integer> purged = new LinkedHashMap<>();
        private int total;
        private boolean cutShort;
        private RuntimeException firstFailure;

        private void failed(RuntimeException ex) {
            if (firstFailure == null) {
                firstFailure = ex;
            }
        }
    }

    /**
     * What one table's batch loop did, and whether it ended because the table was drained or because the
     * deadline arrived. The second half is what {@link #pass} needs to place the cursor: stopping mid
     * table means resuming AT that table, not after it, or the remainder of its backlog waits for the
     * cursor to wrap all the way round.
     */
    private record Drain(int rows, boolean stoppedOnDeadline) {
    }

    /**
     * Every step of one tier, in {@link #PURGE_ORDER}'s order, then the cascades that belong to that
     * tier. The pass runs on an axis the caller has already pinned — it opens no transaction and pins
     * nothing itself, so each batch's borrow picks the axis up on its own.
     *
     * <p>A failure on one table is logged and the pass continues, and the run still fails at the end.
     * That is not politeness: the tables are independent, so one poisoned FK must not cost every table
     * behind it its retention — and with two passes it must not cost the OTHER TIER its retention
     * either, which a thrown exception here would.
     *
     * <p><b>It starts where the last run stopped and ends by clearing the mark.</b> A pass that reaches
     * the end of {@link #PURGE_ORDER} removes its {@link #resumeAt} entry, so the next run is a full
     * sweep from the head again; a pass cut by {@code deadline} leaves the index of the table it was
     * about to purge — or of the table it stopped inside — so the next run continues there. That is the
     * difference between a fleet that drains over three nights and one that re-purges its first three
     * tables forever.
     *
     * <p>A deliberately-skipped step (wrong tier for this pass) costs no deadline check and moves the
     * cursor with the loop, so a cursor left mid-list is always an index this pass actually visits.
     *
     * <p><b>A cut pass runs no cascades, and that is the right call rather than an oversight.</b> The
     * one cascade there is reconciles {@code user_device_trust} against {@code platform.user_device}'s
     * liveness — it is order-independent and idempotent, so deferring it a night costs a night of
     * revoked-but-still-trusted grants and nothing permanent, while running it past a deadline that
     * exists to keep the pass inside its lease would defeat the deadline. It runs on the night the pass
     * completes, which is also the night the platform pass finished its hard deletes.
     */
    private void pass(Tier passTier, String schema, Instant cutoff, Instant deadline, Run run) {
        PassKey pass = new PassKey(passTier, schema);
        int from = resumeAt.getOrDefault(pass, 0);
        if (from > 0) {
            log.info("Soft-delete purge {} pass over {} resumes at {} — the previous run stopped there",
                    passTier, schema, PURGE_ORDER.get(from).table());
        }
        for (int index = from; index < PURGE_ORDER.size(); index++) {
            PurgeStep step = PURGE_ORDER.get(index);
            if (!step.tier().visitedOn(passTier)) {
                continue;
            }
            if (clock.instant().isAfter(deadline)) {
                stopAt(pass, index, run, "before " + step.table());
                return;
            }
            try {
                Drain drain = purgeTable(step.table(), cutoff, deadline);
                run.total += drain.rows();
                ug.co.smsone.shared.metrics.PurgeMetrics.purged(meters, "soft-delete-purge", step.table(),
                        drain.rows());
                if (drain.rows() > 0) {
                    run.purged.merge(step.table(), drain.rows(), Integer::sum);
                }
                if (drain.stoppedOnDeadline()) {
                    // AT this index, not after it: the table still holds a backlog and resuming past it
                    // would leave that backlog for a full cycle of the cursor.
                    stopAt(pass, index, run, "inside " + step.table() + ", which still has a backlog");
                    return;
                }
            } catch (RuntimeException ex) {
                log.error("Soft-delete purge failed on {} ({} pass over {}; continuing with the remaining"
                        + " tables)", step.table(), passTier, schema, ex);
                run.failed(ex);
            }
        }
        // Reached the end: the next run starts from the head again.
        resumeAt.remove(pass);
        for (Cascade cascade : CASCADES.values()) {
            if (cascade.tier() != passTier) {
                continue;
            }
            try {
                run.total += sweepCascade(cascade, run.purged);
            } catch (RuntimeException ex) {
                log.error("Soft-delete cascade sweep failed on {} ({} pass over {})",
                        cascade.childTable(), passTier, schema, ex);
                run.failed(ex);
            }
        }
    }

    /**
     * Records where the pass stopped and says so at WARN. Both halves matter: the cursor is what makes
     * the next run continue, and the log line is the only signal that this installation's nightly
     * retention no longer fits in one pass — a fact that is otherwise indistinguishable from a quiet
     * night, because a cut pass and a clean one both end with a row count and no exception.
     *
     * <p>Not a thrown exception, for {@code OrgMembershipIndexReconciler}'s reason: an incomplete
     * idempotent pass that recorded its position is a smaller problem than a job failing every night in
     * a way nobody can tell from a broken one.
     */
    private void stopAt(PassKey pass, int index, Run run, String where) {
        resumeAt.put(pass, index);
        run.cutShort = true;
        log.warn("Soft-delete purge {} pass over {} stopped at its {} deadline {} — the next run resumes "
                        + "there rather than from the head, so the tables behind it are not starved. A run "
                        + "that hits this regularly has outgrown one pass: measure a batch and re-derive "
                        + "RUN_DEADLINE and the PT30M lease together.",
                pass.tier(), pass.schema(), RUN_DEADLINE, where);
    }

    /**
     * Erasure must reach the search projection too: people and organizations are indexed by event
     * with no delete event to un-index them, so a hard-purged person's email would otherwise remain
     * admin-searchable forever — the exact residue the purge exists to remove. A reconciliation
     * sweep (row's source no longer exists AT ALL, soft-deleted included) rather than id plumbing
     * through the batches; documents un-index themselves on delete and are not swept here.
     *
     * <p>{@code search_document.entity_id} is a varchar holding whatever key {@code entity_type} names
     * (V22), so both joins cast: {@code person.id} on {@code user} rows, {@code organization.id} on
     * {@code organization} rows. The {@code user} discriminator is unchanged — it is the API's word for
     * a human, and only the KEY behind it moved — which is precisely why this sweep had to move with it:
     * a join against a column that no longer exists fails loudly, but one against the wrong key would
     * have matched nothing and silently un-indexed nothing, forever.
     *
     * <p><b>Two statements, and the cast is on the varchar side. Both halves are load-bearing.</b> The
     * single {@code OR}-joined statement this replaced could not be planned as an anti-join at all — an
     * {@code OR} across two {@code NOT EXISTS} arms forecloses the transformation, so Postgres emitted a
     * correlated SubPlan per candidate row — and {@code p.id::text = sd.entity_id} cast the INDEXED
     * side, which makes {@code person_pkey} unusable. Below {@code work_mem} the person arm stopped
     * hashing entirely and degraded to a full {@code Seq Scan on person} per row: measured at 200k
     * people it did not finish in ten minutes, against 212 ms for the form below as a Hash Anti Join.
     * That is a work_mem cliff, which is why it looked fine on a small database — the shape only
     * collapses once {@code person} grows. Casting {@code sd.entity_id} to uuid is safe because the
     * producer writes {@code UUID.toString()} for both discriminators ({@code SearchEventListeners}).
     */
    private void sweepSearchResidue() {
        // Three platform-tier tables in one statement (search_document, person, organization) and all
        // three are named explicitly — ADR 0010 §2. The source tables come from the mapping rather
        // than as literals because sweepResidueOf catches its own failures: an unqualified name that
        // resolved to nothing would raise, be logged, and return 0, so the reconciler would stop
        // un-indexing erased people and say so only in a nightly ERROR line nobody is watching for.
        int removed = sweepResidueOf("user", tables.qualified("person"))
                + sweepResidueOf("organization", tables.qualified("organization"));
        ug.co.smsone.shared.metrics.PurgeMetrics.purged(meters, "soft-delete-purge", "search_document", removed);
        if (removed > 0) {
            log.info("Soft-delete purge un-indexed {} search rows whose source rows are gone", removed);
        }
    }

    /**
     * One discriminator's residue. {@code entityType} and {@code sourceTable} come from the caller —
     * a constant and a name off the entity mapping, never input — the same rule {@link #purgeTable}
     * follows for its interpolated table name.
     */
    private int sweepResidueOf(String entityType, String sourceTable) {
        try {
            return jdbc.update("""
                    delete from platform.search_document sd
                    where sd.entity_type = ?
                      and not exists (select 1 from %s src where src.id = sd.entity_id::uuid)
                    """.formatted(sourceTable), entityType);
        } catch (RuntimeException ex) {
            log.error("Search-residue sweep for '{}' failed (continuing — next run retries)", entityType, ex);
            return 0;
        }
    }

    /**
     * One {@link Cascade}, on the axis its own {@link Cascade#tier()} named — the caller has already
     * pinned it. Reported under the CHILD table's name so the run's log line says which rows actually
     * went — "user_device: 12" and "user_device_trust: 40" are two different facts, and collapsing
     * them would hide a reconciler that is quietly removing grants an event listener should have
     * removed hours earlier.
     *
     * <p>Unbatched, unlike {@link #purgeTable}: the anti-join is bounded by the CHILD table (grants
     * outstanding, thousands) rather than by a retention backlog, and a batched delete would need an
     * ordering column this table does not have. If a cascade child ever grows large enough to hold row
     * locks against live traffic, batch it on the child's own key — do not reach for a {@code limit}
     * with no {@code order by}, which makes successive batches non-deterministic.
     *
     * <p>Not caught here: a failure propagates to {@link #pass}'s handler, which logs it, keeps going,
     * and still fails the run. Same contract as the table's own batches.
     */
    private int sweepCascade(Cascade cascade, Map<String, Integer> purged) {
        int rows = jdbc.update(cascade.sql());
        ug.co.smsone.shared.metrics.PurgeMetrics.purged(meters, "soft-delete-purge", cascade.childTable(), rows);
        if (rows > 0) {
            purged.put(cascade.childTable(), rows);
        }
        return rows;
    }

    /**
     * {@link #PURGE_ORDER} holds BARE table names — they are the same identifiers the metamodel check
     * pins them to, and the same values the purge metric is tagged with — so the schema is resolved
     * here, off the entity mapping, and never written into the list. A hand-kept "these ones are
     * platform's" list beside the order would be a second copy of the tier assignment that nothing
     * compares against the first (ADR 0010 §2); {@link MappedTables} reads the answer from
     * {@code @Table} instead, which {@code PlatformSchemaQualificationTest} pins to
     * {@code docs/DATA_MODEL.md}.
     *
     * <p><b>The {@link Tier} on each step is not a second copy of that.</b> The mapping answers "what
     * is this table CALLED in SQL"; the tier answers "which PASS reaches it", which the name cannot —
     * {@code platform.person} would resolve from a tenant-pinned connection too, and the three split
     * tables have one name and two homes. The two are checked against each other in
     * {@code SoftDeletePurgeJobIntegrationTest}, which is what stops them drifting apart.
     */
    private Drain purgeTable(String table, Instant cutoff, Instant deadline) {
        String qualified = tables.qualified(table);
        String sql = DELETE_BATCH.formatted(qualified,
                GUARDS.getOrDefault(table, "") + heldGuard(qualified));
        int total = 0;
        for (int batch = 0; batch < properties.maxBatches(); batch++) {
            // Checked between batches and never before the first, so every table this pass reaches gets
            // at least one batch. Without that a run whose deadline landed mid-table would leave the
            // cursor parked on a table it never touched, and the cursor would stop meaning progress.
            //
            // The check has to be HERE and not only in pass(): max-batches × batch-size is 50,000 rows
            // on one table, each delete behind a legal_hold anti-join, so a single table can outlast the
            // whole lease on its own. That is the case where the lease expiring is a correctness bug —
            // a second replica acquires the lock and starts deleting the same rows — rather than a slow
            // night, and a per-table check is what makes the deadline able to stop it.
            if (batch > 0 && clock.instant().isAfter(deadline)) {
                return new Drain(total, true);
            }
            int rows = jdbc.update(sql, Timestamp.from(cutoff), properties.batchSize());
            total += rows;
            if (rows < properties.batchSize()) {
                return new Drain(total, false);
            }
        }
        log.warn("Soft-delete purge stopped at the {}-batch cap on {} with a backlog remaining; "
                + "raise app.persistence.soft-delete.max-batches or batch-size", properties.maxBatches(), table);
        // NOT a deadline stop. The batch cap is a per-table bound that leaves the rest of the pass its
        // time, so the cursor must advance past this table — the remaining backlog is tomorrow's, the
        // same as it has always been, and parking the cursor here would starve every table behind it
        // for a reason the cap deliberately does not have.
        return new Drain(total, false);
    }
}
