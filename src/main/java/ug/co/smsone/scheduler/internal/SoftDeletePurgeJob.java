package ug.co.smsone.scheduler.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.persistence.MappedTables;
import ug.co.smsone.shared.persistence.SoftDeleteProperties;
import ug.co.smsone.shared.tenancy.TenantContext;

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
 * <p><b>It runs in two passes, one per tenancy tier</b> — see {@link #purgeExpiredSoftDeletes} for why
 * the tier boundary is an axis boundary and not something a wider pin can paper over, and {@link Tier}
 * for why every entry in {@link #PURGE_ORDER} names its own.
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
     * The org whose axis the TENANT pass borrows. It names no organization deliberately: an org that
     * has never been promoted resolves to the shared {@code tenant_pool}, and a UUID in no
     * {@code organization} row can never resolve to anything else — so this IS the pooled schema's
     * axis, spelled with the only vocabulary {@code TenantContext} has. Same constant, same reasoning
     * as {@code MappedSchemaValidator} and {@code WebhookSecretEncryptionMigrator}: one idiom for "the
     * pool", not three.
     *
     * <p>ADR 0010 Phase 5 turns the single tenant pass into a LOOP over
     * {@code platform.tenant_placement} — one {@code runAs(orgId)} per silo plus this one for whatever
     * is still pooled — because "every tenant" stops being one schema the day the first org is
     * promoted. The two-pass structure below is what makes that a change to {@link #purgeEverything}
     * and to nothing else.
     */
    private static final UUID POOLED_TENANT = new UUID(0L, 0L);

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

    SoftDeletePurgeJob(JdbcTemplate jdbc, SoftDeleteProperties properties, Clock clock,
            io.micrometer.core.instrument.MeterRegistry meters, MappedTables tables) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
        this.meters = meters;
        this.tables = tables;
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
     */
    @Scheduled(cron = "${app.scheduler.soft-delete-purge-cron:0 0 4 * * *}")
    @SchedulerLock(name = "soft-delete-purge", lockAtMostFor = "PT30M")
    public void purgeExpiredSoftDeletes() {
        purgeEverything();
    }

    private void purgeEverything() {
        if (!properties.purgeEnabled()) {
            log.debug("Soft-delete purge is disabled (app.persistence.soft-delete.purge-enabled)");
            return;
        }
        Instant cutoff = clock.instant().minus(properties.retention());

        // Not @Transactional on purpose: every batch commits on its own connection, so a long backlog
        // never becomes one giant transaction holding row locks against live traffic.
        Run run = new Run();
        TenantContext.runAsPlatform(() -> pass(Tier.PLATFORM, cutoff, run));
        // PHASE 5: this single call becomes a loop — one runAs per silo in platform.tenant_placement
        // plus this one for whatever is still pooled. Today the pool IS every tenant, so one pass is
        // the whole fleet; see POOLED_TENANT.
        TenantContext.runAs(POOLED_TENANT, () -> pass(Tier.TENANT, cutoff, run));

        if (run.total > 0) {
            log.info("Soft-delete purge removed {} rows deleted before {} (retention {}): {}",
                    run.total, cutoff, properties.retention(), run.purged);
        } else if (run.firstFailure == null) {
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
        private RuntimeException firstFailure;

        private void failed(RuntimeException ex) {
            if (firstFailure == null) {
                firstFailure = ex;
            }
        }
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
     */
    private void pass(Tier passTier, Instant cutoff, Run run) {
        for (PurgeStep step : PURGE_ORDER) {
            if (!step.tier().visitedOn(passTier)) {
                continue;
            }
            try {
                int rows = purgeTable(step.table(), cutoff);
                run.total += rows;
                ug.co.smsone.shared.metrics.PurgeMetrics.purged(meters, "soft-delete-purge", step.table(), rows);
                if (rows > 0) {
                    run.purged.merge(step.table(), rows, Integer::sum);
                }
            } catch (RuntimeException ex) {
                log.error("Soft-delete purge failed on {} ({} pass; continuing with the remaining tables)",
                        step.table(), passTier, ex);
                run.failed(ex);
            }
        }
        for (Cascade cascade : CASCADES.values()) {
            if (cascade.tier() != passTier) {
                continue;
            }
            try {
                run.total += sweepCascade(cascade, run.purged);
            } catch (RuntimeException ex) {
                log.error("Soft-delete cascade sweep failed on {} ({} pass)", cascade.childTable(), passTier, ex);
                run.failed(ex);
            }
        }
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
    private int purgeTable(String table, Instant cutoff) {
        String qualified = tables.qualified(table);
        String sql = DELETE_BATCH.formatted(qualified,
                GUARDS.getOrDefault(table, "") + heldGuard(qualified));
        int total = 0;
        for (int batch = 0; batch < properties.maxBatches(); batch++) {
            int rows = jdbc.update(sql, Timestamp.from(cutoff), properties.batchSize());
            total += rows;
            if (rows < properties.batchSize()) {
                return total;
            }
        }
        log.warn("Soft-delete purge stopped at the {}-batch cap on {} with a backlog remaining; "
                + "raise app.persistence.soft-delete.max-batches or batch-size", properties.maxBatches(), table);
        return total;
    }
}
