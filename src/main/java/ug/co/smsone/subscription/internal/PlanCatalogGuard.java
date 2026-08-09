package ug.co.smsone.subscription.internal;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.shared.tenancy.TenantFanOut;
import ug.co.smsone.shared.tenancy.placement.TenantPlacement;
import ug.co.smsone.shared.tenancy.placement.TenantPlacements;

/**
 * <strong>The boot-time detector for ADR 0010 §6's worst trap: a plan catalogue that does not answer
 * for the subscriptions pointing at it.</strong>
 *
 * <h2>The trap, verified against this code rather than quoted from the document</h2>
 *
 * <p>§6 item 4 says a missing catalogue snapshot "is a boot-time failure with two opposite symptoms and
 * no error". Both symptoms are real and both are in this package:
 *
 * <ul>
 *   <li>{@link EntitlementResolver#resolve} returns {@code Map.of()} when no plan resolves — its own
 *       comment calls that "seeder not run (fresh empty DB mid-migration)".</li>
 *   <li>From an empty map, {@code EntitlementsImpl.limitOf} returns {@code null} for every key and
 *       {@code requireWithinLimit} does nothing when the limit is null → <b>every quota unlimited</b>.
 *       In the same map {@code hasFeature} is {@code containsKey} → false for every key →
 *       {@code requireFeature} throws → <b>every gated feature 403s</b>. Nothing logs either.</li>
 * </ul>
 *
 * <p><b>And there is a third symptom, quieter than both, which is the one that actually happens.</b>
 * An extracted deployment does not boot with an empty {@code plan} table, because {@code PlanSeeder} is
 * an {@code ApplicationRunner} and creates FREE / PRO / ENTERPRISE on every start — with <em>new
 * UUIDs</em>. So the tenant's {@code org_subscription.plan_id}, which travelled in the dump, matches
 * nothing; {@code resolve} falls through to {@code plans.findByCode("FREE")}; and an ENTERPRISE tenant
 * is served the free entitlements. Not unlimited, not 403 — <em>downgraded</em>, with no error, no log
 * line and no failing request to investigate. That is what an extraction without the catalogue snapshot
 * looks like from the inside, and it is invisible until somebody counts their seats.
 *
 * <h2>So the absence is made loud, at the only moment it is cheap to find</h2>
 *
 * <p>{@code org_subscription.plan_id → plan.id} is one of the five foreign keys ADR 0010 §6 had to cut
 * (it is intra-module and cross-tier, exactly the case AGENTS §1's module clause is structurally blind
 * to), so the database will not catch this. This check is what the constraint used to be, run once at
 * {@code ApplicationReadyEvent} — after {@code PlanSeeder}, which is an {@code ApplicationRunner} and
 * therefore already finished.
 *
 * <h2>What it may REFUSE, and what it may only say — the line, and why it moved</h2>
 *
 * <p>Throwing out of an {@code ApplicationReadyEvent} listener fails
 * {@code SpringApplication.run}: the {@code listeners.ready(...)} block hands it to
 * {@code handleRunFailure} and rethrows, so <em>every pod refuses to start</em>. That is the right
 * price for a deployment which cannot serve anybody correctly, and it is far too high a price for a
 * fact about one tenant out of five thousand. {@code MappedSchemaValidator} already states the rule this
 * class now obeys — "silos are a fleet-wide question that a single pod cannot answer &hellip; ADR 0010
 * Phase 4 puts it in {@code platform.tenant_placement} plus the version floor, where a partial fleet is
 * a queryable fact rather than a failed startup". So:
 *
 * <ul>
 *   <li><b>{@code tenant_pool} refuses.</b> It is the schema every unpromoted tenant shares and the one
 *       this binary must match to serve anybody at all, which is the same scope
 *       {@code MappedSchemaValidator}'s tenant pass takes.</li>
 *   <li><b>A silo refuses only when the registry holds exactly ONE tenant.</b> That installation is the
 *       extracted, single-tenant deployment §6 item 4 is written about: its one silo IS the schema
 *       without which it serves nobody, so the refusal's blast radius and the defect's are the same
 *       one tenant. {@link TenantPlacements#tenantCount()} is the whole discriminator.</li>
 *   <li><b>On a fleet, a dangling silo plan is a WARN naming the tenant, the plans and the query.</b>
 *       One organization silently downgraded is a bad day for one organization; the same condition
 *       converted into a boot failure is 4,999 healthy tenants down for it.</li>
 * </ul>
 *
 * <p><b>And no per-silo probe may throw at all.</b> An ACTIVE placement row is allowed to name a schema
 * that is not there — V57's header says the row must be able to outlive what it names, and
 * {@code TenantMigrationRunner.discoverFleet} calls "a registry row with no schema" a state it reports
 * rather than crashes on. {@code TenantRoutes.readHome} hands that name straight to {@code search_path}
 * with no existence probe, Postgres silently ignores a missing schema on the path, and the unqualified
 * {@code org_subscription} below then raises {@code relation "org_subscription" does not exist}. Before
 * {@link #inspect()} caught it, that stale row failed the boot of every pod in the fleet — for a
 * condition Phase 4 designed to be a {@code select}. Each silo is probed inside its own {@code try} and
 * an unreadable one is reported, never rethrown.
 *
 * <p>The fan-out stops at {@link TenantFanOut#SILO_CEILING} with a log rather than becoming a fleet
 * scan. One indexed query per schema; zero rows on a fresh database.
 */
@Component
class PlanCatalogGuard {

    private static final Logger log = LoggerFactory.getLogger(PlanCatalogGuard.class);

    /**
     * The axis that resolves to {@code tenant_pool}: a UUID no {@code organization} row can hold, so it
     * is the pooled schema's own axis expressed with the only vocabulary {@code TenantContext} has.
     * Lifted from {@code MappedSchemaValidator}, whose javadoc argues it in full.
     */
    private static final UUID POOLED_TENANT_PROBE = new UUID(0L, 0L);

    /**
     * The one statement an operator needs, printed into every warning this class emits. That is what
     * "a queryable fact rather than a failed startup" has to mean in practice: the pod that noticed is
     * gone by the time anybody reads its log, so the log carries the question rather than the answer.
     */
    private static final String THE_QUERY_AN_OPERATOR_RUNS =
            "set search_path to <the tenant's schema>; select s.org_id, s.plan_id from org_subscription s"
            + " where s.deleted_at is null and not exists"
            + " (select 1 from platform.plan p where p.id = s.plan_id);";

    private final JdbcTemplate jdbc;
    private final TenantPlacements placements;

    PlanCatalogGuard(JdbcTemplate jdbc, TenantPlacements placements) {
        this.jdbc = jdbc;
        this.placements = placements;
    }

    /**
     * What one pass over this installation found. Separate from the listener so the finding is a VALUE
     * — the listener decides what to do about it, and a test can assert the fact was found without
     * having to make a pod fail to start to see it.
     *
     * @param pooled dangling plan ids in {@code tenant_pool}, which always refuse
     * @param siloed per-organization dangling plan ids in the silos, which refuse only on a
     *     single-tenant installation
     * @param unreadable silos whose subscriptions could not be read at all, keyed by organization —
     *     a stale ACTIVE placement row is the shipped example, and it is reported, never thrown
     * @param tenants {@link TenantPlacements#tenantCount()} at the time of the pass
     * @param activeSilos how many ACTIVE silos the registry named, whether or not they were probed
     * @param silosSkipped true when the fleet is past {@link TenantFanOut#SILO_CEILING} and only
     *     {@code tenant_pool} was asked
     */
    record Findings(Set<UUID> pooled, Map<UUID, List<UUID>> siloed, Map<UUID, String> unreadable,
            int tenants, int activeSilos, boolean silosSkipped) {

        /**
         * True for the deployment §6 item 4 exists for: one tenant, its own schema, nobody else to take
         * down with it. See the class note on why this is the line between a refusal and a log line.
         */
        boolean servesExactlyOneTenant() {
            return tenants == 1;
        }

        /** The plan ids this installation may not start while naming. */
        Set<UUID> refusals() {
            Set<UUID> refuse = new LinkedHashSet<>(pooled);
            if (servesExactlyOneTenant()) {
                siloed.values().forEach(refuse::addAll);
            }
            return refuse;
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    void everySubscriptionNamesAPlanThisInstallationHas() {
        Findings findings = inspect();
        if (findings.silosSkipped()) {
            log.warn("PlanCatalogGuard checked the pooled schema only: {} active silos is past the {}"
                    + " ceiling, and a boot check must not become a fleet fan-out. The per-tenant answer"
                    + " stays queryable — {}", findings.activeSilos(), TenantFanOut.SILO_CEILING,
                    THE_QUERY_AN_OPERATOR_RUNS);
        }
        if (!findings.servesExactlyOneTenant()) {
            findings.siloed().forEach((orgId, plans) -> log.warn(
                    "organization {} is subscribed to plan id(s) {} that platform.plan does not hold, so"
                    + " EntitlementResolver falls back to findByCode(\"FREE\") and serves it the free"
                    + " entitlements with no error, no log line and no failing request — that tenant is"
                    + " SILENTLY DOWNGRADED until somebody fixes the row. This installation serves {}"
                    + " tenants, so it starts anyway: one organization's dangling plan is one"
                    + " organization's outage, and refusing the boot for it would be everybody's."
                    + " Confirm and repair with: {}", orgId, plans, findings.tenants(),
                    THE_QUERY_AN_OPERATOR_RUNS));
        }
        Set<UUID> dangling = findings.refusals();
        if (dangling.isEmpty()) {
            return;
        }
        throw new IllegalStateException("subscription(s) name plan id(s) " + dangling + " that"
                + " platform.plan does not hold. Nothing in the database enforces this —"
                + " org_subscription.plan_id is one of the five boundary foreign keys ADR 0010 §6 cut —"
                + " and the consequence is SILENT: EntitlementResolver falls back to"
                + " findByCode(\"FREE\") and serves those organizations the free entitlements with no"
                + " error, no log line and no failing request. On a deployment restored from an"
                + " extraction bundle this means the catalogue snapshot (ADR 0010 §6 item 4) was not"
                + " applied and PlanSeeder re-created the plans with new ids; restore the snapshot"
                + " before serving anybody.");
    }

    /**
     * One pass: the pooled schema, then every ACTIVE silo the registry names, each in its own
     * {@code try}. Reads only — the decision is {@link #everySubscriptionNamesAPlanThisInstallationHas}'s.
     */
    Findings inspect() {
        Set<UUID> pooled = new LinkedHashSet<>(danglingIn(POOLED_TENANT_PROBE));
        Map<UUID, List<UUID>> siloed = new LinkedHashMap<>();
        Map<UUID, String> unreadable = new LinkedHashMap<>();
        List<TenantPlacement> silos = TenantContext.callAsPlatform(placements::activeSilos);
        boolean skipped = silos.size() > TenantFanOut.SILO_CEILING;
        if (!skipped) {
            silos.forEach(silo -> probe(silo, siloed, unreadable));
        }
        int tenants = TenantContext.callAsPlatform(placements::tenantCount);
        return new Findings(pooled, siloed, unreadable, tenants, silos.size(), skipped);
    }

    /**
     * One silo, and it cannot throw.
     *
     * <p>{@code RuntimeException} rather than {@code DataAccessException} on purpose, and it is not
     * over-broad: the only exception this method is allowed to let past is the deliberate refusal, and
     * that one is constructed by the caller after every silo has been asked. A schema that has gone
     * missing raises {@code BadSqlGrammarException} here; a placement row whose {@code schema_name}
     * disagrees with its {@code org_id} raises {@code IllegalStateException} out of
     * {@code TenantRoutes.requireThisTenantsSchema} before a statement is even sent. Both are one
     * tenant's registry problem, both belong in the log and in {@link Findings#unreadable()}, and
     * neither is a reason for a pod to refuse to start.
     */
    private void probe(TenantPlacement silo, Map<UUID, List<UUID>> siloed,
            Map<UUID, String> unreadable) {
        try {
            List<UUID> dangling = danglingIn(silo.orgId());
            if (!dangling.isEmpty()) {
                siloed.put(silo.orgId(), dangling);
            }
        } catch (RuntimeException failure) {
            unreadable.put(silo.orgId(), silo.schemaName() + ": " + failure);
            log.warn("PlanCatalogGuard could not read organization {}'s subscriptions out of {}, so"
                    + " whether that tenant's plan resolves is UNKNOWN. An ACTIVE placement row is"
                    + " allowed to outlive the schema it names (V57), and TenantRoutes puts the recorded"
                    + " name on search_path with no existence check — so this is most likely a stale"
                    + " registry row, which is a select to confirm and a row to fix, not a reason for"
                    + " this pod to refuse to start.", silo.orgId(), silo.schemaName(), failure);
        }
    }

    /**
     * The plan ids one tenant schema names and {@code platform.plan} does not have.
     *
     * <p>One statement, evaluated in the database: {@code org_subscription} is read unqualified (it is
     * tenant-tier, so {@code search_path} decides which schema answers) and {@code platform.plan} is
     * named, which is exactly the two forms of address ADR 0010 §2 assigns the two tiers.
     */
    private List<UUID> danglingIn(UUID axis) {
        return TenantContext.callAs(axis, () -> jdbc.queryForList("""
                select distinct s.plan_id
                  from org_subscription s
                 where s.plan_id is not null
                   and s.deleted_at is null
                   and not exists (select 1 from platform.plan p where p.id = s.plan_id)
                """, UUID.class));
    }
}
