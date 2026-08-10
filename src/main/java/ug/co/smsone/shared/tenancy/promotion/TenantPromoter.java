package ug.co.smsone.shared.tenancy.promotion;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import ug.co.smsone.shared.cache.TenantPromotionCaches;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.shared.tenancy.TenantRoutes;
import ug.co.smsone.shared.tenancy.TenantSchemas;
import ug.co.smsone.shared.tenancy.placement.TenantPlacement;
import ug.co.smsone.shared.tenancy.placement.TenantPlacements;
import ug.co.smsone.shared.tenancy.placement.TenantProvisioner;
import ug.co.smsone.shared.tenancy.promotion.TenantRows.Fingerprint;
import ug.co.smsone.shared.tenancy.promotion.TenantTierTables.Plan;

/**
 * <strong>Moves one organization between tenant schemas, in the same database, reversibly.</strong>
 * ADR 0010 §6 hop 0→1 and Phase 5's headline deliverable.
 *
 * <p>{@link #promote} takes a tenant out of the shared {@code tenant_pool} and gives it
 * {@code t_<32hex>} of its own; {@link #demote} puts it back; {@link #reclaim} drops a silo nothing
 * lives in any more. Nothing here is scheduled and nothing triggers on size or load — §8 Q3's default is
 * operator-initiated only, from {@code docs/runbooks/tenant-promotion.md}, and this class is what that
 * runbook drives.
 *
 * <h2>The sequence, and what each step is defending against</h2>
 *
 * <p>§6 states it as <em>freeze → create schema → migrate → copy rows → verify → flip placement → evict
 * caches → unfreeze</em>. Two steps are added here and both earn their place:
 *
 * <ol>
 *   <li><strong>Freeze — twice, because one freeze is not enough.</strong> §6 says the freeze window is
 *       an org-scoped {@code maintenance_window} in RESTRICT mode "<em>but that gates HTTP org paths
 *       only; the notification worker, webhook dispatcher, exchange runners,
 *       {@code OutboxResubmissionJob} and retention purges all write tenant rows OUTSIDE any request
 *       and must be paused for that org too</em>". So the promoter takes THREE locks that together
 *       cover every writer there is: the RESTRICT window ({@link HttpWriteFreeze}) stops org writes at
 *       {@code MaintenanceFilter}; {@code platform.tenant_freeze} stops the three durable queues at
 *       {@code QueueSignals.claim} and every job that asks {@link TenantFreezes}; and
 *       {@code tenant_placement.state = PROVISIONING} takes the tenant (and, while it holds, the pooled
 *       home) out of {@code TenantFanOut}'s sweep list.</li>
 *   <li><strong>Settle.</strong> A short wait after the freeze so requests already in flight finish. It
 *       BOUNDS the in-flight window; it does not close it, and pretending otherwise is what would make
 *       the next step look optional.</li>
 *   <li>Create the schema and migrate it — {@code TenantProvisioner.materialize}, the same call a silo
 *       signup and the fleet runner make, so promotion day executes code that has run many times.</li>
 *   <li><strong>Copy</strong> in one transaction, parents first ({@link TenantRows#copy}).</li>
 *   <li><strong>Verify</strong> in a NEW transaction — per-table row counts AND checksums, both sides
 *       read from one fresh snapshot. Taking a new snapshot is the whole point: inside the copy's own
 *       transaction the comparison is true by construction. See {@link TenantRows#fingerprint}.</li>
 *   <li><strong>Flip</strong> {@code platform.tenant_placement} in one compare-and-swap statement.</li>
 *   <li><strong>Evict</strong> — {@link TenantPromotionCaches#evictAfterPlacementFlip}, after the flip
 *       has committed and before the freeze lifts.</li>
 *   <li><strong>Delete the source rows</strong>, re-checking first that the source still holds exactly
 *       what was copied. This is what makes the promotion a MOVE — see
 *       {@link TenantRows#delete}.</li>
 *   <li><strong>Unfreeze</strong>, in a {@code finally} that runs on every path including the failures.</li>
 * </ol>
 *
 * <h2>Where each failure leaves the tenant</h2>
 *
 * <p>Every step is ordered so that its failure leaves the tenant serving correct rows from SOME schema,
 * and the promoter says which:
 *
 * <ul>
 *   <li><strong>Anything before the flip</strong> — the tenant never stopped serving from the schema it
 *       was already in. The half-built destination is left in place with its rows removed, and
 *       {@link #reclaim} drops it when the operator is satisfied. A verification mismatch lands here on
 *       purpose: it is the detector for a writer the freeze did not catch, and its answer is to abort
 *       while abandoning costs nothing.</li>
 *   <li><strong>The flip itself</strong> — one statement, so it either happened or it did not.</li>
 *   <li><strong>Anything after the flip</strong> — the tenant is already serving verified rows from the
 *       new schema. What can still go wrong is a cache that would not clear or a source delete that
 *       found more rows than were copied, and both are reported in
 *       {@link PromotionReport#warnings()} rather than being allowed to leave the tenant frozen.</li>
 * </ul>
 *
 * <p>The one state that outlives a hard kill is {@code PROVISIONING} plus a {@code tenant_freeze} row.
 * That pair is deliberate and it is why the freeze table exists: the freeze carries a deadline, a holder
 * and a reason, so "this tenant has been PROVISIONING for forty minutes" is answerable with a
 * {@code select} instead of a guess, and the pause it imposes on the queues lapses on its own. The
 * runbook's recovery section is one paragraph because of it.
 *
 * <h2>The ceiling</h2>
 *
 * <p>{@link TenantPromotionProperties#DEFAULT_MAX_SILOS} — 200 per database, refused here, with the
 * derivation in the exception message because the exception message is where the operator will read it.
 * Enforced at promotion and nowhere else: refusing to CREATE the 201st silo is a decision with a human
 * behind it, while truncating a fan-out's home list would silently stop doing the 201st tenant's work
 * (see {@code TenantFanOut.SILO_CEILING}, which logs and does not enforce).
 */
@Component
public class TenantPromoter {

    private static final Logger log = LoggerFactory.getLogger(TenantPromoter.class);

    /** ADR 0010 §3.1's silo name shape, as a Postgres regex — the catalogue half of the ceiling count. */
    private static final String SILO_SCHEMA_PATTERN = "^t_[0-9a-f]{32}$";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final TenantPlacements placements;
    private final TenantProvisioner provisioner;
    private final TenantTierTables tables;
    private final TenantRows rows;
    private final TenantFreezes freezes;
    private final TenantPromotionCaches caches;
    private final ObjectProvider<HttpWriteFreeze> httpFreeze;
    private final TenantPromotionProperties properties;

    TenantPromoter(JdbcTemplate jdbc, TransactionTemplate transactions, TenantPlacements placements,
            TenantProvisioner provisioner, TenantTierTables tables, TenantRows rows,
            TenantFreezes freezes, TenantPromotionCaches caches,
            ObjectProvider<HttpWriteFreeze> httpFreeze, TenantPromotionProperties properties) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.placements = placements;
        this.provisioner = provisioner;
        this.tables = tables;
        this.rows = rows;
        this.freezes = freezes;
        this.caches = caches;
        this.httpFreeze = httpFreeze;
        this.properties = properties;
    }

    /**
     * {@code tenant_pool} → {@code t_<32hex>}: gives one organization a schema of its own.
     *
     * @throws TenantPromotionException if the tenant is not pooled, if the silo ceiling is reached, if
     *     the destination already holds rows, or if the copy does not verify. In every one of those the
     *     tenant is left serving from {@code tenant_pool}, unchanged.
     */
    public PromotionReport promote(UUID orgId) {
        TenantPlacement placement = servingPlacement(orgId);
        if (TenantSchemaNames.isSilo(placement.schemaName())) {
            throw new TenantPromotionException("organization " + orgId + " already serves from its own"
                    + " schema '" + placement.schemaName() + "'. Promoting it again would copy it onto"
                    + " itself; demote() is the reverse and reclaim() drops an abandoned silo.");
        }
        String silo = TenantSchemas.siloSchema(orgId);
        refuseAtTheCeiling(silo);
        return move(orgId, placement.schemaName(), silo, true);
    }

    /**
     * {@code t_<32hex>} → {@code tenant_pool}: the reverse, and ADR 0010 §6's "<em>reversible by copying
     * back and flipping one row</em>" made real.
     *
     * <p>It is the same eight steps with the schemas swapped, and deliberately not a second
     * implementation — a reversal that shares no code with the thing it reverses is a reversal nobody
     * has actually tested. The one asymmetry is that the destination is not created or migrated:
     * {@code tenant_pool} exists, is at head, and is where every other tenant is living right now.
     *
     * <p>The silo is NOT dropped here. Its rows are removed once the copy back has verified, so what is
     * left is an empty schema at head — which {@link #reclaim} drops when the operator has confirmed the
     * tenant is well. Splitting them is the same judgement {@code TenantSchemaMigrator} makes when it
     * sets {@code cleanDisabled(true)}: dropping a tenant's schema is a runbook step with a human in it,
     * not something a method call away from a code path that just moved data.
     */
    public PromotionReport demote(UUID orgId) {
        TenantPlacement placement = servingPlacement(orgId);
        if (!TenantSchemaNames.isSilo(placement.schemaName())) {
            throw new TenantPromotionException("organization " + orgId + " is already pooled in '"
                    + placement.schemaName() + "'; there is nothing to demote it from.");
        }
        return move(orgId, placement.schemaName(), TenantSchemas.TENANT_POOL, false);
    }

    /**
     * Drops a silo that no tenant serves from and that holds no rows — the last step of a demotion, run
     * separately and by hand.
     *
     * <p>Both preconditions are checked and neither is a formality. A schema some placement still names
     * is a schema a request may route to; a schema with rows in it is either a demotion whose delete did
     * not finish or — the case worth stopping for — a MISROUTE, ADR 0010 §1's worst failure, where a row
     * landed in a schema its {@code org_id} disagrees with. Dropping it would destroy the evidence along
     * with the row, so this refuses and names the tables.
     *
     * <p>One {@code drop schema … cascade} per transaction, always: a tenant schema holds ~439 lockable
     * relations against a cluster's ~6,400 slots, so the fifteenth in one transaction is the measured
     * ceiling and the sixteenth takes the whole block down with "out of shared memory" (§5.12). One call,
     * one schema, autocommit — do not batch this.
     */
    public void reclaim(String siloSchema) {
        String silo = TenantSchemas.requireSiloSchema(siloSchema);
        List<String> serving = placements.schemas().stream().filter(silo::equals).toList();
        if (!serving.isEmpty()) {
            throw new TenantPromotionException("schema '" + silo + "' is still named by a row in"
                    + " platform.tenant_placement, so something may still route a request to it. Demote"
                    + " the tenant first; reclaiming a schema a placement names is how a tenant loses"
                    + " every row it has.");
        }
        Map<String, Long> remaining = TenantContext.callAsPlatform(
                () -> rows.countEverything(tables.planFor(silo)));
        if (!remaining.isEmpty()) {
            throw new TenantPromotionException("schema '" + silo + "' still holds rows and will not be"
                    + " dropped: " + remaining + ". Either a demotion's delete did not finish, or a write"
                    + " landed in a schema whose tenant had already left it — which is a misrouted write"
                    + " (ADR 0010 §1), and the rows are the only evidence of it. Read them before"
                    + " dropping anything.");
        }
        jdbc.execute("drop schema \"" + silo + "\" cascade");
        log.info("reclaimed tenant schema {} — empty, unreferenced, dropped", silo);
    }

    /**
     * The move, in both directions. {@code createDestination} is the only branch: a promotion builds the
     * schema it is moving into, a demotion moves into one that has always been there.
     */
    private PromotionReport move(UUID orgId, String from, String to, boolean createDestination) {
        refuseInsideATransaction();
        Instant started = Instant.now();
        List<String> warnings = new ArrayList<>();
        String reason = "ADR 0010 hop 0->1: moving organization " + orgId + " from " + from + " to " + to;

        // The freeze is taken FIRST and before anything is read, so nothing this promotion looks at can
        // have been written by a worker that had not yet seen the pause.
        // The deadline comes back from the DATABASE rather than being computed here, and both freezes
        // then end at the same instant: `platform.tenant_freeze.expires_at` is what every reader
        // compares against, so it is also what this promoter must give up at.
        Instant deadline = freezes.freeze(orgId, reason, holder(), properties.freeze());
        UUID window = openHttpFreeze(orgId, deadline, from, to);

        // BOTH freezes come off here, not just the queue one. A refusal below changes nothing about the
        // tenant, and a tenant that is refused a move must not be left in a RESTRICT window until
        // `ends_at` — that is app.tenancy.promotion.freeze (PT30M by default) of write outage bought by
        // an operation that did nothing. Wrapping this rather than moving openHttpFreeze into the try
        // below keeps the window open across `beginRelocation` itself, which is where a writer racing
        // the claim would otherwise slip in.
        try {
            if (!placements.beginRelocation(orgId, from)) {
                throw new TenantPromotionException("organization " + orgId + " is not ACTIVE in '" + from
                        + "' — either something moved it since this call started, another promotion"
                        + " holds it, or a CUTOVER to another database is in flight (beginRelocation"
                        + " refuses those in SQL). `select * from platform.tenant_placement where org_id"
                        + " = '" + orgId + "'` and `select * from platform.tenant_cutover where org_id ="
                        + " '" + orgId + "'`. Nothing was moved and both freezes have been lifted.");
            }
        } catch (RuntimeException refused) {
            closeHttpFreeze(orgId, window, warnings);
            freezes.thaw(orgId);
            throw refused;
        }

        Moved moved;
        try {
            settle();
            // A promotion BUILDS its destination; a demotion moves into tenant_pool, which exists, is at
            // head and is where every other tenant is living — so its version is read rather than
            // produced. Running Flyway over the pool here would be idempotent and would also re-stamp
            // five thousand placement rows to say what they already said.
            String version = createDestination ? provisioner.materialize(to)
                    : TenantContext.callAsPlatform(() -> placements.versionOf(to));
            moved = copyVerifyAndFlip(orgId, from, to, version, warnings, deadline);
        } catch (RuntimeException failure) {
            // Put the tenant back in service where it never stopped being. Best-effort and swallowed:
            // this runs while another exception is propagating, and losing the real cause to a secondary
            // failure here would cost the operator the only sentence that says what went wrong.
            try {
                placements.abandonRelocation(orgId, from, describe(failure));
            } catch (RuntimeException recovery) {
                log.error("tenant {} could not be returned to ACTIVE in {} after a failed move; it is"
                        + " PROVISIONING and every background sweep is standing down for it until the"
                        + " registry is fixed by hand (see docs/runbooks/tenant-promotion.md)",
                        orgId, from, recovery);
            }
            throw failure;
        } finally {
            // Both freezes come off on every path. A tenant left frozen by a failed promotion is an
            // outage the failure did not cause and nobody asked for.
            closeHttpFreeze(orgId, window, warnings);
            freezes.thaw(orgId);
        }

        // The report is built HERE and not inside the try, so that the freeze duration covers the
        // unfreeze itself and so that a warning raised while lifting the freeze still reaches the
        // operator — PromotionReport copies its warning list, and a report built one line earlier would
        // have frozen it before the last step that can add to it had run.
        PromotionReport report = new PromotionReport(orgId, from, to, moved.verified(), moved.removed(),
                Duration.between(started, Instant.now()), warnings);
        log.info("tenant {} moved from {} to {}: {} rows across {} tables, writes held for {} ms{}",
                orgId, from, to, report.rowsMoved(), report.nonEmpty().size(),
                report.freezeHeld().toMillis(),
                report.warnings().isEmpty() ? "" : " WITH WARNINGS: " + report.warnings());
        return report;
    }

    /** What the copy achieved, before the freeze has come off and the report can be written. */
    private record Moved(Map<String, Fingerprint> verified, Map<String, Long> removed) {
    }

    /** Steps 4 through 8 — the part that is identical whichever way the tenant is going. */
    private Moved copyVerifyAndFlip(UUID orgId, String from, String to, String version,
            List<String> warnings, Instant deadline) {
        Plan source = TenantContext.callAsPlatform(() -> tables.planFor(from));
        Plan destination = TenantContext.callAsPlatform(() -> tables.planFor(to));
        rows.assertSameShape(source, destination);
        refuseIfTheDestinationIsOccupied(orgId, destination);

        Instant copyStarted = Instant.now();
        transactions.executeWithoutResult(status -> rows.copy(source, destination, orgId));

        // A NEW transaction, so the source is read from a snapshot taken AFTER the copy committed — the
        // only snapshot in which a write that raced the copy is visible at all.
        Map<String, Fingerprint> verified = inOneTransaction(() -> {
            Map<String, Fingerprint> before = rows.fingerprint(source, orgId);
            Map<String, Fingerprint> after = rows.fingerprint(destination, orgId);
            refuseOnAnyDifference(orgId, from, to, before, after);
            return before;
        });

        // Not just "is there time left" but "is there a ROUTE_TTL left". The flip only moves THIS
        // process immediately; every other pod is holding a memoized home for up to that long, so the
        // freeze has to outlive the flip by at least one TTL or the source rows are deleted while
        // somebody is still reading them. Checked BEFORE the flip because it is the last moment the
        // answer is "abort and nothing has moved".
        requireTimeLeft(orgId, deadline, TenantRoutes.routeTtl(),
                "the copy verified but the freeze has less than one route TTL (" + TenantRoutes.routeTtl()
                        + ") left, so the placement was NOT flipped — nothing has moved and the tenant is"
                        + " still serving from " + from);

        if (!placements.completeRelocation(orgId, from, to, version)) {
            throw new TenantPromotionException("the placement flip for organization " + orgId + " matched"
                    + " no row: it was expected PROVISIONING in '" + from + "'. Nothing has moved — the"
                    + " destination holds a verified copy that reclaim() will drop.");
        }
        log.info("tenant {} now placed in {} at version {} ({} rows verified in {} ms)",
                orgId, to, version, verified.values().stream().mapToLong(Fingerprint::rows).sum(),
                Duration.between(copyStarted, Instant.now()).toMillis());

        // After the flip has committed, before the freeze lifts — the ordering TenantPromotionCaches'
        // own javadoc argues for, and both wrong orders are reachable from here.
        try {
            caches.evictAfterPlacementFlip(orgId);
        } catch (RuntimeException eviction) {
            warnings.add("cache eviction failed after the flip (" + eviction + ") — this process may"
                    + " still answer from values it resolved under " + from + "; restart the pods");
            log.error("tenant {} moved but its caches did not clear", orgId, eviction);
        }

        waitForEveryProcessToSeeTheFlip(orgId, from, to);

        return new Moved(verified, removeFromSource(orgId, source, verified, warnings));
    }

    /**
     * <strong>Holds the freeze for one {@link TenantRoutes#ROUTE_TTL} after the flip, and this is the
     * step that stops promotion being a data-loss event on a multi-pod deployment.</strong>
     *
     * <p>{@code TenantRoutes} memoizes a tenant's home for that long, per process. The flip is one row
     * in one database and nothing pushes it anywhere: {@code TenantPromotionCaches
     * .evictAfterPlacementFlip} drops the memo on the pod running the promotion and on no other. So for
     * up to a TTL after the flip, every other replica still addresses this tenant's statements to
     * {@code from}.
     *
     * <p>That is survivable only while the freeze is up and the source rows are still there — a stale
     * pod then reads rows that exist and are correct, and writes nothing, because the RESTRICT window
     * stops HTTP writes and {@code platform.tenant_freeze} stops the queues and the jobs. The two steps
     * that end that grace are the source delete and the unfreeze, and both come after this wait. Without
     * it, the very next request on a stale pod reads an empty schema and the one after that writes into
     * one this tenant has left.
     *
     * <p>A wait and not a broadcast, deliberately: a Valkey message that fails to arrive misroutes a
     * tenant silently, where a timer that runs long makes a promotion slower and nothing else. Thirty
     * seconds against a freeze budget of thirty minutes is not a cost worth optimising.
     */
    private void waitForEveryProcessToSeeTheFlip(UUID orgId, String from, String to) {
        Duration ttl = TenantRoutes.routeTtl();
        log.info("tenant {} is flipped to {}; holding the freeze {} more so every other process drops its"
                + " memoized route to {} before the source rows go (ADR 0010 §6 hop 0->1)",
                orgId, to, ttl, from);
        try {
            Thread.sleep(ttl.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            // The flip has COMMITTED, so this is not abortable and must not be dressed up as one. The
            // tenant is live on its new schema; what has been skipped is the grace other pods needed.
            throw new TenantPromotionException("interrupted while holding the freeze for the route TTL"
                    + " after organization " + orgId + " was flipped to '" + to + "'. THE FLIP STANDS —"
                    + " the tenant is serving from its new schema. Other processes may still route it to"
                    + " '" + from + "' for up to " + ttl + "; the source rows have NOT been deleted, so"
                    + " nothing is lost. Wait out that interval before removing them by hand, or restart"
                    + " the pods.", interrupted);
        }
    }

    /**
     * Runs a read in one transaction and insists on an answer. {@code TransactionTemplate.execute}
     * is nullable by signature; every use here returns a map it built, so a null is a framework
     * surprise and not a value this class should carry into a comparison.
     */
    private <T> T inOneTransaction(java.util.function.Supplier<T> work) {
        return java.util.Objects.requireNonNull(transactions.execute(status -> work.get()),
                "a transaction that was asked for a fingerprint returned nothing");
    }

    /**
     * Deletes the tenant out of the schema it has left, but only while the source still holds exactly
     * what was copied.
     *
     * <p>The re-check is what stops a delete from being the step that loses data. Between the
     * verification and here the tenant has been flipped, so a write that got past the freeze in that
     * window landed in the OLD schema and is not in the new one — deleting it would destroy the only
     * copy. Finding a difference therefore leaves the source rows exactly where they are, reports it,
     * and lets the operator reconcile a handful of rows against a tenant that is otherwise fully live.
     */
    private Map<String, Long> removeFromSource(UUID orgId, Plan source, Map<String, Fingerprint> verified,
            List<String> warnings) {
        Map<String, Fingerprint> now = inOneTransaction(() -> rows.fingerprint(source, orgId));
        List<String> drifted = new ArrayList<>();
        now.forEach((table, fingerprint) -> {
            if (!fingerprint.matches(verified.get(table))) {
                drifted.add(table + ": verified " + verified.get(table) + ", now " + fingerprint);
            }
        });
        if (!drifted.isEmpty()) {
            warnings.add("the source schema " + source.schema() + " changed after the copy was verified,"
                    + " so its rows were LEFT IN PLACE rather than deleted: " + drifted + ". The tenant is"
                    + " live on its new schema; these rows are writes that got past the freeze and have"
                    + " to be reconciled by hand before the source copy is removed.");
            log.error("tenant {} was moved but {} drifted after verification; source rows kept",
                    orgId, drifted);
            return Map.of();
        }
        return inOneTransaction(() -> rows.delete(source, orgId));
    }

    /**
     * The silo ceiling (§8 Q1), refused with its derivation, because the message is the only place an
     * operator hitting this will read it.
     *
     * <p>Counted over the UNION of the catalogue and the registry — the same union
     * {@code TenantMigrationRunner.discoverFleet()} takes, and for the same reason. A schema in the
     * catalogue that no placement names still costs planning time and lock slots in every fan-out that
     * enumerates schemas; a placement naming a schema that does not exist yet is a promotion in flight
     * whose seat has to be counted or two concurrent promotions could both be the 200th.
     */
    private void refuseAtTheCeiling(String candidate) {
        List<String> silos = TenantContext.callAsPlatform(() -> {
            List<String> catalogue = jdbc.queryForList("""
                    select schema_name from information_schema.schemata where schema_name ~ ?
                    """, String.class, SILO_SCHEMA_PATTERN);
            List<String> registered = placements.schemas().stream()
                    .filter(TenantSchemaNames::isSilo)
                    .toList();
            return java.util.stream.Stream.concat(catalogue.stream(), registered.stream())
                    .distinct()
                    .toList();
        });
        if (silos.contains(candidate) || silos.size() < properties.maxSilos()) {
            return;
        }
        throw new TenantPromotionException("""
                This database already holds %d tenant silos and the ceiling is %d \
                (app.tenancy.promotion.max-silos). Promoting another one is refused.

                WHY THERE IS A CEILING AT ALL (ADR 0010 §8 Q1, measured on this schema, not inferred). \
                A silo is one more branch in every cross-tenant fan-out the platform still runs, and two \
                costs scale with branch count without amortizing:
                  * PLANNING ~0.5-0.66 ms per branch, paid on EVERY execution — a query text that grows \
                with the fleet defeats pgjdbc's statement cache and Postgres' plan cache alike. Measured: \
                50 branches 12-24 ms, 200 branches 69-100 ms, 500 branches 313-389 ms.
                  * LOCKS 2.004 entries per branch, against ~6,400 cluster-wide slots \
                (max_locks_per_transaction 64 x max_connections 100). Measured: 50 -> ~102, 200 -> ~402, \
                500 -> ~1,002. Exhaust them and the transaction dies with "out of shared memory".
                At 200 silos that is ~130 ms of planning and ~403 lock entries per fan-out transaction, \
                which is the budget this number was chosen to sit inside.

                WHAT TO DO. Raising the ceiling is a MEASUREMENT, not a decision: replay the UNION \
                benchmark at N = 50 / 100 / 200 against real tenant_pool + silo data (§8 Q1 asks for \
                exactly this), then set app.tenancy.promotion.max-silos to what it justifies. Past 50 \
                silos the intended answer is not a wider fan-out at all — it is \
                platform.ticket_index (§5.1, §8 Q2), after which the planning half of the derivation \
                above stops applying. Or demote a silo that no longer needs one: \
                docs/runbooks/tenant-promotion.md.
                """.formatted(silos.size(), properties.maxSilos()));
    }

    /**
     * Refuses to copy into a schema that already holds this tenant's rows.
     *
     * <p>The case it catches is a re-run: a promotion that copied, failed before the flip and is being
     * tried again. Copying a second time would collide on primary keys for most tables and — for the
     * few with no unique constraint the copy would violate — silently double them. Deleting first
     * instead was considered and rejected: an operator re-running a promotion is not asking this class
     * to delete rows it did not put there, and {@link #reclaim} plus a fresh promotion is one runbook
     * step with a human in it.
     */
    private void refuseIfTheDestinationIsOccupied(UUID orgId, Plan destination) {
        Map<String, Fingerprint> existing = inOneTransaction(() -> rows.fingerprint(destination, orgId));
        Map<String, Long> occupied = new LinkedHashMap<>();
        existing.forEach((table, fingerprint) -> {
            if (fingerprint.rows() > 0) {
                occupied.put(table, fingerprint.rows());
            }
        });
        if (!occupied.isEmpty()) {
            throw new TenantPromotionException("schema '" + destination.schema() + "' already holds rows"
                    + " for organization " + orgId + " " + occupied + ". A second copy would collide on"
                    + " every primary key it hits and silently double whatever it does not. If this is a"
                    + " re-run of a failed promotion, reclaim() the schema first and start again.");
        }
    }

    /** The verification, and the only thing standing between a copy and a tenant that lost rows. */
    private static void refuseOnAnyDifference(UUID orgId, String from, String to,
            Map<String, Fingerprint> source, Map<String, Fingerprint> destination) {
        List<String> differences = new ArrayList<>();
        source.forEach((table, expected) -> {
            Fingerprint actual = destination.get(table);
            if (!expected.matches(actual)) {
                differences.add(table + ": " + from + " has " + expected + ", " + to + " has " + actual);
            }
        });
        if (differences.isEmpty()) {
            return;
        }
        throw new TenantPromotionException("the copy of organization " + orgId + " does not match its"
                + " source, so NOTHING has been flipped and the tenant is still serving from " + from
                + ". Differences: " + differences + "."
                + " A row-count difference means rows were written to (or removed from) the source while"
                + " the copy was running; a checksum difference with equal counts means a row CHANGED,"
                + " which a count alone cannot see. Either way a writer got past the freeze — find it"
                + " before retrying, because a retry will race the same writer (ADR 0010 §6 hop 0->1).");
    }

    /** The tenant as the registry sees it, refusing anything that is not simply serving. */
    private TenantPlacement servingPlacement(UUID orgId) {
        if (orgId == null) {
            throw new IllegalArgumentException("a promotion names an organization; null names nothing");
        }
        TenantPlacement placement = TenantContext.callAsPlatform(() -> placements.find(orgId))
                .orElseThrow(() -> new TenantPromotionException("organization " + orgId + " has no row in"
                        + " platform.tenant_placement. A tenant with no recorded home is one nothing has"
                        + " placed — it is not a tenant this can move, and creating a placement for it"
                        + " here would announce a tenant nobody registered (ADR 0010 §4.3)."));
        if (!placement.isActive()) {
            throw new TenantPromotionException("organization " + orgId + " is " + placement.state()
                    + " in '" + placement.schemaName() + "', not ACTIVE. Only a tenant that is currently"
                    + " serving can be moved: a PROVISIONING one is already being moved by somebody, and"
                    + " a FAILED one has a home nothing has proved fit. `select * from"
                    + " platform.tenant_freeze where org_id = '" + orgId + "'` says whether a promotion"
                    + " still holds it or a process died holding it.");
        }
        // ADR 0011: the promoter's copy is INSERT … SELECT between two schemas on ONE connection, and
        // a tenant served from another database has no pair of schemas one connection can see. The
        // reverse cutover is the move that brings it home (docs/runbooks/tenant-cutover.md).
        if (!TenantPlacement.PRIMARY_DATASOURCE.equals(placement.dataSourceName())) {
            throw new TenantPromotionException("organization " + orgId + " is served from datasource '"
                    + placement.dataSourceName() + "', not the platform database, so a schema-to-schema"
                    + " copy cannot reach its rows. Run a reverse cutover to 'primary' first"
                    + " (docs/runbooks/tenant-cutover.md), then promote or demote it here.");
        }
        return placement;
    }

    private UUID openHttpFreeze(UUID orgId, Instant until, String from, String to) {
        HttpWriteFreeze freeze = httpFreeze.getIfAvailable();
        if (freeze == null) {
            freezes.thaw(orgId);
            throw new TenantPromotionException("no HttpWriteFreeze implementation is registered, so org"
                    + " writes arriving over HTTP would not be blocked while this tenant's rows were"
                    + " copied. That is the half of the freeze ADR 0010 §6 names FIRST, and a promotion"
                    + " without it copies rows a request is still writing.");
        }
        // On the org's own axis: maintenance_window is tenant-tier, so the row lands in the schema the
        // tenant is in right now — which is also why it is copied to the destination along with
        // everything else, and why the gate stays continuous across the flip.
        return TenantContext.callAs(orgId, () -> freeze.open(orgId, until,
                "Moving this organization's data between storage schemas (" + from + " -> " + to
                        + "). Reads are unaffected; writes resume automatically."));
    }

    private void closeHttpFreeze(UUID orgId, UUID window, List<String> warnings) {
        if (window == null) {
            return;
        }
        try {
            // After the flip, "the org's axis" is the DESTINATION schema — where the copy put this very
            // row. Before it (a failed promotion), it is the source, where it was written. Either way
            // the axis resolves to the schema the row is in, which is the property that makes one call
            // correct on both paths.
            TenantContext.runAs(orgId, () -> httpFreeze.getObject().close(orgId, window));
        } catch (RuntimeException failure) {
            warnings.add("the RESTRICT maintenance window " + window + " could not be cancelled ("
                    + failure + "); this organization's writes stay blocked until it expires or an"
                    + " operator cancels it: DELETE /api/v1/orgs/" + orgId + "/maintenance/" + window);
            log.error("tenant {} could not have its promotion freeze window cancelled", orgId, failure);
        }
    }

    /**
     * The settle wait. Not a drain — nothing here tracks in-flight requests, and calling it one would be
     * exactly the comfort that makes the fingerprint comparison look optional.
     */
    private void settle() {
        Duration drain = properties.drain();
        if (drain.isZero() || drain.isNegative()) {
            return;
        }
        try {
            Thread.sleep(drain.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new TenantPromotionException("interrupted while waiting for in-flight requests to"
                    + " finish; nothing has been copied", interrupted);
        }
    }

    /**
     * @param headroom how much of the freeze must still be ahead of us, not merely any at all — the
     *     post-flip route grace is a step that has to fit inside the window like every other one
     */
    private void requireTimeLeft(UUID orgId, Instant deadline, Duration headroom, String what) {
        if (Instant.now().plus(headroom).isBefore(deadline)) {
            return;
        }
        throw new TenantPromotionException("the freeze on organization " + orgId + " lapses at " + deadline
                + " (app.tenancy.promotion.freeze = " + properties.freeze() + "): " + what + "."
                + " Background writers resumed the moment it expired, so continuing would flip a tenant"
                + " onto a copy that may already be stale. Raise the budget for this tenant's size and"
                + " run it again.");
    }

    private static void refuseInsideATransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("a tenant move must run OUTSIDE a transaction: it issues DDL,"
                    + " runs Flyway, needs the copy and the verification in SEPARATE transactions (a"
                    + " verification inside the copy's own snapshot is true by construction and proves"
                    + " nothing), and pins tenant axes — which TenantContext refuses inside one.");
        }
    }

    /** Which process is holding the tenant, for the operator who finds a freeze row and wants a name. */
    private static String holder() {
        String host = Optional.ofNullable(System.getenv("HOSTNAME")).orElse("unknown-host");
        return TenantPromoter.class.getSimpleName() + "@" + host + "/" + ProcessHandle.current().pid();
    }

    private static String describe(Throwable failure) {
        return failure.getClass().getSimpleName() + ": " + failure.getMessage();
    }
}
