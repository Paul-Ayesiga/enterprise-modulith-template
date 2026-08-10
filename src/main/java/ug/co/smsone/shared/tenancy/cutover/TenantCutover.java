package ug.co.smsone.shared.tenancy.cutover;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import ug.co.smsone.shared.cache.TenantPromotionCaches;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.shared.tenancy.TenantRoutes;
import ug.co.smsone.shared.tenancy.TenantSchemas;
import ug.co.smsone.shared.tenancy.placement.PlacementState;
import ug.co.smsone.shared.tenancy.placement.TenantPlacement;
import ug.co.smsone.shared.tenancy.placement.TenantPlacements;
import ug.co.smsone.shared.tenancy.promotion.HttpWriteFreeze;
import ug.co.smsone.shared.tenancy.promotion.TenantFreezes;
import ug.co.smsone.shared.tenancy.promotion.TenantRows;
import ug.co.smsone.shared.tenancy.promotion.TenantRows.Fingerprint;
import ug.co.smsone.shared.tenancy.promotion.TenantTierTables;
import ug.co.smsone.shared.tenancy.promotion.TenantTierTables.Plan;
import ug.co.smsone.shared.tenancy.cutover.TenantCutovers.Cutover;
import ug.co.smsone.shared.tenancy.cutover.TenantCutovers.State;

/**
 * <strong>Moves one tenant's silo to another DATABASE, with a freeze measured in seconds whatever the
 * tenant weighs, reversible until decommission.</strong> ADR 0010 §6 hop 1→2's mechanism, decided in
 * ADR 0011 §7 and implemented here — every replication behaviour a comment below asserts was executed
 * against two scratch Postgres 18.4 containers before this class existed.
 *
 * <p>{@code TenantPromoter} moves a tenant between schemas by copying rows itself, which is why its
 * freeze must cover the whole copy. This class never copies a row: logical replication does the heavy
 * lifting ONLINE ({@code CREATE PUBLICATION … FOR TABLES IN SCHEMA} on the source, a
 * {@code copy_data = true} subscription on the destination), and the freeze covers only the tail —
 * drain, verify, flip — which is what makes its width independent of tenant size. Only a SILO cuts
 * over: a row-filtered publication under default PK replica identity breaks every tenant's UPDATE and
 * DELETE fleet-wide at DML time (§7.1, measured), so a pooled tenant promotes first (hop 0→1→2).
 *
 * <h2>The sequence, and where each failure leaves the tenant</h2>
 *
 * <p>The invariant the ordering serves: <strong>the tenant keeps serving from the SOURCE until the
 * flip commits, and nothing between create-publication and flip is load-bearing for serving.</strong>
 *
 * <ol>
 *   <li><strong>Record</strong> the cutover ({@code platform.tenant_cutover}, V60). From this row on,
 *       the migration runner refuses this schema (§7.3's measured crash-loop-then-silent-skip) and
 *       {@code TenantPlacements.beginRelocation} refuses promotions of this tenant. Placement stays
 *       ACTIVE: jobs and queues keep running.</li>
 *   <li><strong>Build the destination</strong> — {@link CutoverDestinations}: the real
 *       {@code TenantSchemaMigrator}, the §5.1 minimal platform schema, the emptiness proof, and the
 *       flyway-history truncate without which the initial sync wedges (measured; see
 *       {@code CutoverDestinations#makeSyncable}). <em>Failure: nothing routed, nothing frozen —
 *       the streams and the row are torn back down, retry freely.</em></li>
 *   <li><strong>Publish, subscribe, sync</strong> — online, unbounded by any freeze. <em>Failure or
 *       timeout: the stream keeps running and the row stays SYNCING; re-running
 *       {@link #moveToDatasource} resumes the wait, {@link #abort} tears it down.</em></li>
 *   <li><strong>Freeze</strong> — the promoter's three locks verbatim: {@code tenant_freeze} (queues
 *       and jobs), the RESTRICT {@code maintenance_window} (HTTP writes), placement
 *       {@code PROVISIONING} (fan-outs). Then settle, so in-flight requests finish.</li>
 *   <li><strong>Drain</strong>: capture {@code pg_current_wal_lsn()} ONCE, wait for the slot's
 *       {@code confirmed_flush_lsn} to cross it. Never a wait for equality with the live head — the
 *       walsender decodes the whole fleet's WAL, so the head is moved by every other tenant's writes
 *       and that wait can starve; the captured value is a fixed point (measured, §7.2 step 6).</li>
 *   <li><strong>Verify</strong> — {@code TenantRows} counts AND checksums, both databases, every
 *       table. <em>Failure: abort as above; still frozen, nothing flipped, unfreeze and retry.</em></li>
 *   <li><strong>Flip</strong> — {@code datasource_name} in one compare-and-swap, atomically with the
 *       cutover row's SYNCING→CUT. Then evict, and hold the freeze one full route TTL: the same
 *       arithmetic as promotion, now protecting the datasource half of the route.</li>
 *   <li><strong>Reverse-replicate BEFORE unfreezing.</strong> Forward subscription dropped first
 *       (loop prevention — its slot goes with it, observed), destination publishes, source subscribes
 *       {@code WITH (copy_data = false)} — {@code copy_data = true} back into the origin that still
 *       holds every row storms duplicate-key on every PK (measured). <strong>The ordering is the
 *       rollback guarantee: no destination write exists before the reverse stream does</strong>, so
 *       there is no instant at which flipping back loses a committed write. Only then unfreeze.
 *       <br>That guarantee is arithmetic, not intent: the flip is refused unless the freeze still
 *       holds the route-TTL hold <em>plus</em> {@link #REVERSE_STREAM_BUDGET} — see
 *       {@link #frozenTail()}, which is the repair for a budget that used to reserve exactly the hold
 *       and then spend exactly the hold, leaving the reverse stream to be established with nothing
 *       left.</li>
 *   <li><strong>Watch</strong> for {@code app.tenancy.cutover.watch-window}, then {@link #decommission}
 *       — or {@link #rollBack}, which is cheap precisely because of step 8.</li>
 * </ol>
 *
 * <p>The one state a hard kill can leave that needs judgement is CUT with no reverse stream: the
 * tenant is LIVE on the destination and the freeze is the only thing keeping rollback safe, so this
 * class then deliberately lets the freeze LAPSE rather than lifting it — see the step-8 catch — and
 * the runbook's "stuck at CUT" section is the human half of that decision.
 *
 * <p>Operator-initiated only, from {@code docs/runbooks/tenant-cutover.md} (§10 Q6) — nothing here is
 * scheduled, and the conninfo strings are arguments because only the operator knows both networks
 * ({@link CutoverLinks}).
 */
@Component
public class TenantCutover {

    private static final Logger log = LoggerFactory.getLogger(TenantCutover.class);

    /**
     * The freeze budget. A constant where promotion's is config, because the thing promotion sizes
     * against — tenant weight — is exactly what this design removed from the window: the frozen work
     * is a drain of already-streamed WAL, a fingerprint pass, and one row update. Ten minutes bounds
     * a busy fleet's drain hiccup without leaving a crashed run's freeze in force for promotion's 30.
     */
    private static final Duration FREEZE_BUDGET = Duration.ofMinutes(10);

    /** Promotion's settle, same number, same honesty: it bounds the in-flight window, not closes it. */
    private static final Duration SETTLE = Duration.ofSeconds(2);

    /**
     * The freeze that must remain AFTER the route-TTL hold, for the work the hold is followed by:
     * three statements, one of which dials the other database and creates a replication slot there
     * ({@code CREATE SUBSCRIPTION}). A minute is far more than the measured cost (sub-second on a
     * healthy pair) and small against {@link #FREEZE_BUDGET} — it is sized for the pathological dial,
     * because what it buys is the class's central claim.
     *
     * <p><b>Why it exists at all.</b> {@code requireTimeLeft} used to reserve exactly one route TTL
     * and {@link #holdOneRouteTtl} then spent exactly that, so a slow-draining run could pass the
     * check with one TTL left, sleep it away, and establish the reverse stream with ~0 ms of freeze
     * remaining — the instant the freeze lapses, HTTP writes and queues resume against a destination
     * whose changes flow nowhere. That is precisely "a destination write that exists nowhere else",
     * which step 8's ordering exists to make impossible. The claim was right and the arithmetic did
     * not implement it; this constant is the difference.
     */
    private static final Duration REVERSE_STREAM_BUDGET = Duration.ofMinutes(1);

    /**
     * Slack between the drain's deadline and the tail's, so a verification that runs long is refused
     * by {@code requireTimeLeft} with its own message rather than by the drain timing out first.
     */
    private static final Duration VERIFY_HEADROOM = Duration.ofSeconds(5);

    /**
     * The ONLINE phase's patience — initial tablesync plus catch-up, while the tenant serves
     * unfrozen from the source. Generous because nothing is held: a timeout leaves the stream
     * running and the row SYNCING, and re-running the move resumes the same wait.
     */
    private static final Duration ONLINE_SYNC_BUDGET = Duration.ofHours(2);

    private static final Duration POLL = Duration.ofMillis(500);

    private final TransactionTemplate transactions;
    private final TenantPlacements placements;
    private final TenantFreezes freezes;
    private final TenantCutovers cutovers;
    private final TenantTierTables tables;
    private final TenantRows rows;
    private final CutoverPools pools;
    private final CutoverDestinations destinations;
    private final TenantPromotionCaches caches;
    private final ObjectProvider<HttpWriteFreeze> httpFreeze;
    private final Duration watchWindow;

    TenantCutover(TransactionTemplate transactions, TenantPlacements placements, TenantFreezes freezes,
            TenantCutovers cutovers, TenantTierTables tables, TenantRows rows, CutoverPools pools,
            CutoverDestinations destinations, TenantPromotionCaches caches,
            ObjectProvider<HttpWriteFreeze> httpFreeze,
            // ADR 0011 §10 Q4's default. How long the reverse stream must have insured the move
            // before decommission may destroy the source copy — the thing it insures against is a
            // serving defect discovered on day three, which no shorter window can.
            @Value("${app.tenancy.cutover.watch-window:P7D}") Duration watchWindow) {
        this.transactions = transactions;
        this.placements = placements;
        this.freezes = freezes;
        this.cutovers = cutovers;
        this.tables = tables;
        this.rows = rows;
        this.pools = pools;
        this.destinations = destinations;
        this.caches = caches;
        this.httpFreeze = httpFreeze;
        this.watchWindow = watchWindow == null || watchWindow.isNegative() || watchWindow.isZero()
                ? Duration.ofDays(7) : watchWindow;
    }

    /**
     * Moves {@code orgId}'s silo to {@code targetDatasource} — {@code 'primary'} included, which IS
     * the reverse cutover: the same machinery with the roles swapped, not a second implementation,
     * for the reason {@code TenantPromoter.demote} gives about reversals nobody has tested.
     *
     * <p>Re-running after an online-phase failure resumes the existing stream; a failure inside the
     * freeze window unfreezes and leaves the stream syncing, so the retry pays only a new freeze.
     *
     * @throws TenantCutoverException naming, in every case, where the tenant is left serving from
     */
    public CutoverReport moveToDatasource(UUID orgId, String targetDatasource, CutoverLinks links) {
        refuseInsideATransaction();
        if (links == null) {
            throw new IllegalArgumentException("a cutover needs its CutoverLinks up front — see the"
                    + " record's javadoc for why they cannot be derived from JDBC URLs");
        }
        Instant started = Instant.now();
        TenantPlacement placement = servingPlacement(orgId);
        String silo = requireSiloTenant(orgId, placement);
        String source = placement.dataSourceName();
        String target = targetDatasource;
        if (source.equals(target)) {
            throw new TenantCutoverException("organization " + orgId + " is already served from"
                    + " datasource '" + source + "'; there is nothing to move.");
        }

        JdbcTemplate sourceDb = new JdbcTemplate(pools.poolFor(source));
        JdbcTemplate targetDb = new JdbcTemplate(pools.poolFor(target));
        TenantReplication sourceSide = new TenantReplication(sourceDb, "datasource '" + source + "'");
        TenantReplication targetSide = new TenantReplication(targetDb, "datasource '" + target + "'");
        requireLogicalWal(sourceDb, source);
        requireLogicalWal(targetDb, target);
        sourceSide.abandonedSlots(inFlightSchemas());

        boolean resuming = claimOrResume(orgId, silo, source, target, sourceSide, targetSide);
        if (!resuming) {
            try {
                destinations.build(pools.poolFor(target), silo,
                        TenantPlacement.PRIMARY_DATASOURCE.equals(target));
                sourceSide.createPublication(silo);
                targetSide.createSubscription(silo, links.sourceConninfo(),
                        TenantReplication.publicationName(silo), true);
            } catch (RuntimeException failure) {
                // §7.2 step 3's failure story, literally: DROP SUBSCRIPTION takes its publisher slot
                // with it (observed as the NOTICE), the publication and the row follow, and nothing
                // else happened — the tenant never stopped serving from the source.
                quietly("forward subscription", () -> targetSide.dropSubscriptionAndSlot(silo, source)
                        .ifPresent(slot -> failure.addSuppressed(new TenantCutoverException(
                                "replication slot '" + slot + "' is ORPHANED on datasource '" + source
                                        + "' and pins WAL there until reclaimAbandonedSlots('" + source
                                        + "') or a hand-run pg_drop_replication_slot removes it"))));
                quietly("forward publication", () -> sourceSide.dropPublication(silo));
                quietly("cutover row", () -> platform(() -> cutovers.end(orgId)));
                // The third slot-leaking path (ADR 0011 §7.2 step 3, the half its failure story does
                // not cover): CREATE SUBSCRIPTION creates the slot on the PUBLISHER first, so a
                // failure after that leaves a slot with no subscription anywhere to drop it — the
                // teardown above finds nothing to do and the WAL accrues. The row is gone by now, so
                // this tenant's slot is no longer explained by an in-flight cutover and the sweep can
                // see it. Attached to the failure because that is what the operator is reading.
                quietly("abandoned slots on '" + source + "'",
                        () -> sourceSide.dropAbandonedSlots(inFlightSchemas())
                                .forEach(slot -> failure.addSuppressed(new TenantCutoverException(
                                        "replication slot '" + slot + "' was left behind on datasource"
                                                + " '" + source + "' by this failed build and has been"
                                                + " dropped — no WAL is being pinned by it"))));
                throw failure;
            }
        }

        // Online: nothing frozen, the tenant fully served from the source. Sizing note from the
        // measurement (§7.2 step 4): catch-up scales with FLEET-WIDE write volume, not tenant size —
        // the walsender decodes the whole WAL stream.
        waitFor("initial table sync of " + silo + " on '" + target + "'",
                Instant.now().plus(ONLINE_SYNC_BUDGET),
                () -> targetSide.unsyncedRelations(silo) == 0);
        String syncedTo = sourceSide.captureLsn();
        waitFor("pre-freeze catch-up to " + syncedTo, Instant.now().plus(ONLINE_SYNC_BUDGET),
                () -> sourceSide.confirmedPast(silo, syncedTo));
        Duration syncing = Duration.between(started, Instant.now());

        // The freeze window. The promoter's choreography: tenant_freeze first, so nothing this
        // cutover drains can have been written by a worker that had not yet seen the pause.
        List<String> warnings = new ArrayList<>();
        String reason = "ADR 0011 hop 1->2: moving organization " + orgId + " ('" + silo + "') from"
                + " datasource '" + source + "' to '" + target + "'";
        Instant freezeTaken = Instant.now();
        Instant deadline = platform(() -> freezes.freeze(orgId, reason, holder(), FREEZE_BUDGET));
        UUID window = openHttpFreeze(orgId, deadline, source, target);
        claimForTheWindow(orgId, silo, window);

        boolean flipped = false;
        boolean reverseUp = false;
        Map<String, Fingerprint> verified;
        try {
            settle();
            String captured = sourceSide.captureLsn();
            waitFor("frozen-tail drain to " + captured, drainBy(deadline),
                    () -> sourceSide.confirmedPast(silo, captured));
            verified = verifyBothSides(sourceDb, targetDb, silo, orgId, source, target);
            requireTimeLeft(orgId, deadline, frozenTail(), "the copy verified but the freeze has less"
                    + " left than the post-flip tail needs (one route TTL of hold, then the reverse"
                    + " stream), so the placement was NOT flipped — nothing has moved and the tenant is"
                    + " still served from '" + source + "'");
            flip(orgId, silo, source, target);
            flipped = true;
            evict(orgId, warnings);
            holdOneRouteTtl(orgId, source, target);

            // Reverse BEFORE unfreeze — the ordering IS the rollback guarantee (class note, step 8).
            targetSide.dropSubscriptionAndSlot(silo, source).ifPresent(slot -> warnings.add(
                    "the forward subscription was dropped DETACHED from its slot, so replication slot '"
                            + slot + "' is orphaned on datasource '" + source + "' and pins WAL there:"
                            + " reclaimAbandonedSlots('" + source + "') drops it"));
            targetSide.createPublication(silo);
            sourceSide.createSubscription(silo, links.targetConninfo(),
                    TenantReplication.publicationName(silo), false);
            platform(() -> cutovers.markWatching(orgId));
            reverseUp = true;
            // The tail budget above says this cannot happen; saying so is not the same as measuring it.
            // A run that got here late DID leave a window in which the destination was writable with
            // no reverse stream, and the operator must hear that from the report rather than infer it.
            if (Instant.now().isAfter(deadline)) {
                warnings.add("the freeze lapsed at " + deadline + ", before the reverse stream was"
                        + " established — writes committed on '" + target + "' in that window may exist"
                        + " nowhere else, so rollBack() is not lossless until they are accounted for."
                        + " Raise app.tenancy.route-ttl headroom or investigate what ran long.");
            }
        } catch (RuntimeException failure) {
            if (!flipped) {
                // Put the tenant back in service where it never stopped being. Best-effort and
                // swallowed, for the promoter's stated reason: this runs while the real cause is
                // propagating.
                try {
                    platform(() -> placements.abandonRelocation(orgId, silo, describe(failure)));
                } catch (RuntimeException recovery) {
                    log.error("tenant {} could not be returned to ACTIVE after a failed cutover; it is"
                            + " PROVISIONING and background sweeps stand down for it until the registry"
                            + " is fixed by hand (docs/runbooks/tenant-cutover.md)", orgId, recovery);
                }
            }
            throw failure;
        } finally {
            if (!flipped || reverseUp) {
                closeHttpFreeze(orgId, window, warnings);
                platform(() -> freezes.thaw(orgId));
            } else {
                // Flipped, but the reverse stream did not come up. Unfreezing now would let the
                // destination take writes that exist NOWHERE else — the exact state step 8's ordering
                // exists to make impossible — so the freeze is left to lapse on its own deadline
                // (bounded by FREEZE_BUDGET; V58's whole design is that an orphaned freeze expires).
                // The operator chooses: re-establish the reverse stream and thaw, or accept that
                // rollback now costs a full reverse cutover. Runbook: "stuck at CUT".
                log.error("tenant {} is LIVE on '{}' but its reverse stream is not up. The freeze is"
                        + " deliberately left to lapse at {} so no unprotected destination write can"
                        + " exist yet — docs/runbooks/tenant-cutover.md, section 'stuck at CUT'.",
                        orgId, target, deadline);
            }
        }

        Duration freezeHeld = Duration.between(freezeTaken, Instant.now());
        CutoverReport report = new CutoverReport(orgId, silo, source, target, verified, syncing,
                freezeHeld, warnings);
        log.info("tenant {} cut over from '{}' to '{}': {} rows across {} tables verified, synced in"
                + " {} ms, writes held {} ms, WATCHING for {}{}",
                orgId, source, target, report.rowsMoved(), report.nonEmpty().size(),
                syncing.toMillis(), freezeHeld.toMillis(), watchWindow,
                warnings.isEmpty() ? "" : " WITH WARNINGS: " + warnings);
        return report;
    }

    /**
     * Flips a WATCHING tenant back to its source datasource — "the last cheap reversal" as code, and
     * it is cheap only because step 8 ran: the reverse stream has kept the origin current since
     * before the destination served its first write, so this is drain → verify → flip, the same
     * frozen tail in the other direction (§7.2 step 10).
     *
     * <p>The destination's copy is left in place and reported: it is evidence if the rollback was
     * prompted by a data defect, and {@link #reclaimAbandonedCopy} is its one-call disposal.
     */
    public CutoverReport rollBack(UUID orgId) {
        refuseInsideATransaction();
        Cutover cutover = requireCutover(orgId);
        if (cutover.state() != State.WATCHING) {
            throw new TenantCutoverException("organization " + orgId + " is " + cutover.state()
                    + ", not WATCHING. A SYNCING cutover has nothing to roll back — abort() it; a CUT"
                    + " one has no reverse stream yet, and what to do about that is a judgement call:"
                    + " docs/runbooks/tenant-cutover.md, 'stuck at CUT'.");
        }
        TenantPlacement placement = servingPlacement(orgId);
        String silo = cutover.schemaName();
        String source = cutover.sourceDatasource();
        String target = cutover.targetDatasource();
        if (!placement.dataSourceName().equals(target)) {
            throw new TenantCutoverException("the placement says organization " + orgId + " is served"
                    + " from '" + placement.dataSourceName() + "' but the WATCHING cutover row says '"
                    + target + "' — the two registries disagree and no automatic move is safe."
                    + " `select * from platform.tenant_placement where org_id = '" + orgId + "'` and"
                    + " the runbook's recovery section.");
        }

        JdbcTemplate sourceDb = new JdbcTemplate(pools.poolFor(source));
        JdbcTemplate targetDb = new JdbcTemplate(pools.poolFor(target));
        TenantReplication sourceSide = new TenantReplication(sourceDb, "datasource '" + source + "'");
        TenantReplication targetSide = new TenantReplication(targetDb, "datasource '" + target + "'");

        List<String> warnings = new ArrayList<>();
        String reason = "ADR 0011 §7.2 step 10: rolling organization " + orgId + " ('" + silo
                + "') back from datasource '" + target + "' to '" + source + "'";
        Instant freezeTaken = Instant.now();
        Instant deadline = platform(() -> freezes.freeze(orgId, reason, holder(), FREEZE_BUDGET));
        UUID window = openHttpFreeze(orgId, deadline, target, source);
        claimForTheWindow(orgId, silo, window);

        boolean flippedBack = false;
        Map<String, Fingerprint> verified;
        try {
            settle();
            // The write side is the TARGET now, and so is the reverse stream's publisher — the slot
            // this drain watches lives where the WAL being drained is.
            String captured = targetSide.captureLsn();
            waitFor("rollback drain to " + captured, drainBy(deadline),
                    () -> targetSide.confirmedPast(silo, captured));
            verified = verifyBothSides(sourceDb, targetDb, silo, orgId, source, target);
            requireTimeLeft(orgId, deadline, frozenTail(), "the copies verified but the freeze has"
                    + " less left than the post-flip tail needs (one route TTL of hold, then the"
                    + " stream teardown that must finish before any pod may write again), so nothing"
                    + " was flipped back — the tenant is still served from '" + target + "'");
            platform(() -> transactions.execute(status -> {
                if (!placements.completeDatasourceMove(orgId, silo, target, source)) {
                    throw new TenantCutoverException("the rollback flip for organization " + orgId
                            + " matched no row (expected PROVISIONING on '" + target + "')."
                            + " Nothing has moved; the tenant is still served from '" + target + "'.");
                }
                return null;
            }));
            flippedBack = true;
            evict(orgId, warnings);
            holdOneRouteTtl(orgId, target, source);

            // Still frozen: the streams come down before any pod may write again, so no write can
            // land on the abandoned destination after the flip-back.
            sourceSide.dropSubscriptionAndSlot(silo, target).ifPresent(slot -> warnings.add(
                    "the reverse subscription was dropped DETACHED from its slot, so replication slot '"
                            + slot + "' is orphaned on datasource '" + target + "' and pins WAL there:"
                            + " reclaimAbandonedSlots('" + target + "') drops it"));
            targetSide.dropPublication(silo);
            sourceSide.dropPublication(silo);
            platform(() -> cutovers.end(orgId));
            warnings.add("the destination copy of '" + silo + "' is still on datasource '" + target
                    + "' — stale from this moment. reclaimAbandonedCopy(" + orgId + ", '" + target
                    + "') drops it once whatever prompted this rollback is understood.");
        } catch (RuntimeException failure) {
            if (!flippedBack) {
                try {
                    platform(() -> placements.abandonRelocation(orgId, silo, describe(failure)));
                } catch (RuntimeException recovery) {
                    log.error("tenant {} could not be returned to ACTIVE after a failed rollback",
                            orgId, recovery);
                }
            }
            throw failure;
        } finally {
            // Unlike the forward move there is no post-flip stream to establish — the source needs
            // no insurance against itself — so the freeze always comes off here.
            closeHttpFreeze(orgId, window, warnings);
            platform(() -> freezes.thaw(orgId));
        }

        Duration freezeHeld = Duration.between(freezeTaken, Instant.now());
        CutoverReport report = new CutoverReport(orgId, silo, target, source, verified,
                Duration.ZERO, freezeHeld, warnings);
        log.info("tenant {} rolled back from '{}' to '{}': {} rows verified, writes held {} ms{}",
                orgId, target, source, report.rowsMoved(), freezeHeld.toMillis(),
                warnings.isEmpty() ? "" : " WITH WARNINGS: " + warnings);
        return report;
    }

    /**
     * Tears down a cutover that never flipped (SYNCING) — or one flipped BACK whose teardown did not
     * finish. Refuses a tenant currently served from the target: that is {@link #rollBack}'s job,
     * because aborting there would destroy the reverse stream that makes rolling back safe.
     *
     * <p>Every drop is attempted even when an earlier one fails — an unreachable database must not
     * leave the reachable one's objects behind — and what could not be dropped is reported, because
     * each leftover subscription's slot retains WAL somewhere until a human deals with it.
     *
     * @return the operator's to-do list: what could not be dropped, and every slot this abort left
     *     orphaned. <strong>Empty is the only clean answer.</strong> It is returned and not merely
     *     logged because the row is deleted here whatever happens — an abort that leaves objects
     *     behind and then removes the only record that they exist is how a slot becomes invisible
     *     (the abandoned-slot sweep is the other half of that repair, and this list names it)
     */
    public List<String> abort(UUID orgId) {
        refuseInsideATransaction();
        Cutover cutover = requireCutover(orgId);
        TenantPlacement placement = platform(() -> placements.find(orgId))
                .orElseThrow(() -> new TenantCutoverException("organization " + orgId + " has a cutover"
                        + " row but no placement at all — nothing can say where it is served from."
                        + " Registry surgery is a runbook step, not an abort."));
        if (placement.dataSourceName().equals(cutover.targetDatasource())) {
            throw new TenantCutoverException("organization " + orgId + " is SERVED from the cutover's"
                    + " target '" + cutover.targetDatasource() + "' — the flip stands, so there is"
                    + " nothing here to abort. rollBack() is the reversal; decommission() is the"
                    + " completion.");
        }
        String silo = cutover.schemaName();
        String source = cutover.sourceDatasource();
        String target = cutover.targetDatasource();
        JdbcTemplate sourceDb = new JdbcTemplate(pools.poolFor(source));
        JdbcTemplate targetDb = new JdbcTemplate(pools.poolFor(target));
        TenantReplication sourceSide = new TenantReplication(sourceDb, "datasource '" + source + "'");
        TenantReplication targetSide = new TenantReplication(targetDb, "datasource '" + target + "'");

        List<String> leftovers = new ArrayList<>();
        attempt(leftovers, "forward subscription on '" + target + "'",
                () -> targetSide.dropSubscriptionAndSlot(silo, source).ifPresent(slot ->
                        leftovers.add("orphaned replication slot '" + slot + "' on '" + source
                                + "' (pins WAL there): reclaimAbandonedSlots('" + source + "')")));
        attempt(leftovers, "reverse subscription on '" + source + "'",
                () -> sourceSide.dropSubscriptionAndSlot(silo, target).ifPresent(slot ->
                        leftovers.add("orphaned replication slot '" + slot + "' on '" + target
                                + "' (pins WAL there): reclaimAbandonedSlots('" + target + "')")));
        attempt(leftovers, "publication on '" + target + "'", () -> targetSide.dropPublication(silo));
        attempt(leftovers, "publication on '" + source + "'", () -> sourceSide.dropPublication(silo));
        if (placement.state() == PlacementState.PROVISIONING) {
            // A freeze-window crash left the claim; the tenant never left the source, so back it goes.
            platform(() -> placements.abandonRelocation(orgId, silo, "cutover aborted"));
        }
        platform(() -> freezes.thaw(orgId));
        platform(() -> cutovers.end(orgId));
        // Only now: while the row lived, this tenant's slots were "explained" and the sweep left them
        // alone. With it gone, a slot from a CREATE SUBSCRIPTION that died mid-statement — the one
        // shape no drop above can reach, because there is no subscription to drop — becomes visible
        // and removable. Both sides, because an abort can be called from either direction.
        for (String datasource : List.of(source, target)) {
            attempt(leftovers, "abandoned-slot sweep on '" + datasource + "'",
                    () -> replicationOn(datasource).dropAbandonedSlots(inFlightSchemas()));
        }
        if (leftovers.isEmpty()) {
            log.info("cutover of tenant {} aborted clean; it never stopped serving from '{}'. The"
                    + " half-built copy on '{}' remains — reclaimAbandonedCopy drops it.",
                    orgId, source, target);
        } else {
            log.error("cutover of tenant {} aborted, but these could not be dropped and someone must"
                    + " finish by hand (each undropped subscription's slot retains WAL on its"
                    + " publisher): {} — docs/runbooks/tenant-cutover.md, abort section.",
                    orgId, leftovers);
        }
        return List.copyOf(leftovers);
    }

    /**
     * Ends a WATCHING cutover for good: reverse stream down, publications down, the stale SOURCE copy
     * dropped — after which the reversal stops being cheap and becomes a fresh cutover the other way.
     * Refused before the watch window has run (§10 Q4): the window is the insurance term, and
     * shortening it is an ADR question, not a flag.
     *
     * <p>The one drop that can face an unreachable publisher is the reverse subscription (its
     * publisher is the destination); {@code TenantReplication} then takes the measured
     * {@code DISABLE → slot_name=NONE → DROP} escape, which leaves a slot pinning WAL on the
     * destination. That slot used to be unreachable by every shipped call: the destination is where
     * the tenant is SERVED from, so {@link #reclaimAbandonedCopy} refuses it by design. So this method
     * now sweeps for it itself, once the cutover row is gone and the slot is therefore explained by
     * nothing — and whatever the sweep could not reach comes back in the return value.
     *
     * <p>Idempotent by construction, because a decommission that dies half way is a state a human then
     * has to finish: every drop tolerates its object being gone already, so re-running it is the
     * repair rather than a second incident.
     *
     * @return what somebody still has to deal with — an orphaned slot the sweep could not drop, a
     *     database that was unreachable. Empty on a clean decommission
     */
    public List<String> decommission(UUID orgId) {
        refuseInsideATransaction();
        Cutover cutover = requireCutover(orgId);
        if (cutover.state() != State.WATCHING) {
            throw new TenantCutoverException("organization " + orgId + " is " + cutover.state()
                    + ", not WATCHING — there is no completed, insured move to decommission."
                    + " SYNCING aborts; CUT is the runbook's judgement call.");
        }
        Instant due = cutover.cutAt().plus(watchWindow);
        if (Instant.now().isBefore(due)) {
            throw new TenantCutoverException("the watch window (app.tenancy.cutover.watch-window = "
                    + watchWindow + ") runs until " + due + " — until then the reverse stream is the"
                    + " cheap rollback, and decommissioning destroys it. Come back then, or roll back"
                    + " now if something is wrong.");
        }
        TenantPlacement placement = servingPlacement(orgId);
        if (!placement.dataSourceName().equals(cutover.targetDatasource())) {
            throw new TenantCutoverException("the placement serves organization " + orgId + " from '"
                    + placement.dataSourceName() + "' but the cutover row moved it to '"
                    + cutover.targetDatasource() + "'. Decommissioning would drop the copy the tenant"
                    + " is READING — registries disagree, runbook recovery section first.");
        }
        String silo = cutover.schemaName();
        String source = cutover.sourceDatasource();
        String target = cutover.targetDatasource();
        JdbcTemplate sourceDb = new JdbcTemplate(pools.poolFor(source));
        JdbcTemplate targetDb = new JdbcTemplate(pools.poolFor(target));
        TenantReplication sourceSide = new TenantReplication(sourceDb, "datasource '" + source + "'");
        TenantReplication targetSide = new TenantReplication(targetDb, "datasource '" + target + "'");

        List<String> warnings = new ArrayList<>();
        Optional<String> orphan = sourceSide.dropSubscriptionAndSlot(silo, target);
        targetSide.dropPublication(silo);
        sourceSide.dropPublication(silo);
        // The stale copy. Rows are EXPECTED here — this is the one sanctioned drop of a schema that
        // holds them, which is why it does not reuse TenantPromoter.reclaim (whose emptiness refusal
        // is its whole point) and why it only runs behind the two registry checks above. One schema
        // per transaction, always (ADR 0010 §5.12's measured lock ceiling). `if exists` so a re-run
        // after a half-finished decommission completes instead of failing on its own progress.
        sourceDb.execute("drop schema if exists " + quote(silo) + " cascade");
        platform(() -> cutovers.end(orgId));
        // Row gone, so the tenant's own slot is no longer explained by an in-flight cutover: the
        // escape's orphan (on the TARGET, where the tenant now serves) becomes visible to the sweep
        // and droppable by it. Attempted rather than assumed — if the target is the side that was
        // unreachable, this cannot work either, and then the operator gets the sentence instead.
        if (orphan.isPresent()) {
            attempt(warnings, "orphaned slot '" + orphan.get() + "' on '" + target + "'", () -> {
                if (!targetSide.dropAbandonedSlots(inFlightSchemas()).contains(orphan.get())) {
                    throw new TenantCutoverException("replication slot '" + orphan.get() + "' is still"
                            + " on datasource '" + target + "' and pins WAL there — it was not"
                            + " inactive, or it is gone already. Check"
                            + " `select * from pg_replication_slots` there and drop it by hand"
                            + " (docs/runbooks/tenant-cutover.md, decommission).");
                }
            });
        }
        log.info("tenant {} decommissioned from '{}': the silo now exists only on '{}'. Reversal from"
                + " here is a fresh cutover.{}", orgId, source, target,
                warnings.isEmpty() ? "" : " WITH WARNINGS: " + warnings);
        return List.copyOf(warnings);
    }

    /**
     * Drops a copy of the tenant's schema that nothing serves — a rolled-back cutover's destination,
     * or an aborted one's half-build. Separate from the operations above for the promoter's
     * {@code reclaim} reason: destroying bytes is a runbook step with a human in it, never a side
     * effect. Also drops the tenant-shaped orphan slot the escape hatch may have left there.
     */
    public void reclaimAbandonedCopy(UUID orgId, String datasourceName) {
        refuseInsideATransaction();
        String silo = TenantSchemas.siloSchema(orgId);
        Optional<Cutover> inFlight = platform(() -> cutovers.find(orgId));
        if (inFlight.isPresent()) {
            throw new TenantCutoverException("a cutover of organization " + orgId + " is "
                    + inFlight.get().state() + " — while it lives, every copy is load-bearing."
                    + " Finish it (decommission), reverse it (rollBack) or abort it first.");
        }
        TenantPlacement placement = platform(() -> placements.find(orgId)).orElse(null);
        if (placement != null && placement.dataSourceName().equals(datasourceName)) {
            throw new TenantCutoverException("datasource '" + datasourceName + "' is where organization "
                    + orgId + " is SERVED from — dropping this copy would drop the tenant. If the"
                    + " intent is to leave this datasource, that is a cutover, not a reclaim.");
        }
        JdbcTemplate db = new JdbcTemplate(pools.poolFor(datasourceName));
        new TenantReplication(db, "datasource '" + datasourceName + "'").dropPublication(silo);
        db.query("select pg_drop_replication_slot(slot_name) from pg_replication_slots"
                        + " where slot_name = ? and not active",
                (rs, row) -> rs.getString(1), TenantReplication.subscriptionName(silo));
        db.execute("drop schema if exists " + quote(silo) + " cascade");
        log.info("reclaimed the abandoned copy of tenant {} on datasource '{}'", orgId, datasourceName);
    }

    /** The abandoned-slot detector, runnable on demand — also runs before every move. */
    public List<String> reportAbandonedSlots(String datasourceName) {
        return replicationOn(datasourceName).abandonedSlots(inFlightSchemas());
    }

    /**
     * <strong>Drops every slot {@link #reportAbandonedSlots} names on one datasource</strong> — the
     * reclaim the detector had no counterpart for, and the answer to "no shipped call can remove
     * this" for all three paths that leave one: a {@code dropSubscriptionAndSlot} escape, a
     * decommission that died after taking one, and a {@code CREATE SUBSCRIPTION} that failed after
     * its slot existed on the publisher.
     *
     * <p>Deliberately <strong>not</strong> gated on the datasource being one nobody is served from,
     * unlike {@link #reclaimAbandonedCopy}. That refusal protects rows; this drops a slot, which
     * holds none — and the two orphans that matter most (the escape's, and a failed build's) both
     * land on a database the tenant IS serving from, so a serving-datasource refusal here would rule
     * out exactly the cases the method exists for. What keeps it safe is narrower and stronger: an
     * in-flight cutover's slots are subtracted by name, and an attached one is {@code active} and
     * cannot be dropped at all (measured, {@code 55006}).
     *
     * <p>Operator-initiated, from the runbook, like everything else here.
     *
     * @return the slots dropped; empty means the database was already clean
     */
    public List<String> reclaimAbandonedSlots(String datasourceName) {
        refuseInsideATransaction();
        return replicationOn(datasourceName).dropAbandonedSlots(inFlightSchemas());
    }

    private TenantReplication replicationOn(String datasourceName) {
        return new TenantReplication(new JdbcTemplate(pools.poolFor(datasourceName)),
                "datasource '" + datasourceName + "'");
    }

    // ---------------------------------------------------------------------------------------------

    /** Records the row, or proves the existing one is resumable. True = the streams already exist. */
    private boolean claimOrResume(UUID orgId, String silo, String source, String target,
            TenantReplication sourceSide, TenantReplication targetSide) {
        Optional<Cutover> existing = platform(() -> cutovers.find(orgId));
        if (existing.isEmpty()) {
            if (!platform(() -> cutovers.begin(orgId, silo, source, target))) {
                throw new TenantCutoverException("another cutover of organization " + orgId
                        + " was recorded between this call's checks — nothing was done."
                        + " `select * from platform.tenant_cutover where org_id = '" + orgId + "'`.");
            }
            return false;
        }
        Cutover cutover = existing.get();
        if (cutover.state() != State.SYNCING || !cutover.targetDatasource().equals(target)
                || !cutover.sourceDatasource().equals(source)) {
            throw new TenantCutoverException("organization " + orgId + " already has a cutover row: "
                    + cutover.state() + " from '" + cutover.sourceDatasource() + "' to '"
                    + cutover.targetDatasource() + "'. This call asked for '" + source + "' -> '"
                    + target + "', which is not a resume of that — decommission, rollBack or abort the"
                    + " existing move first. Nothing was done.");
        }
        if (!sourceSide.publicationExists(silo) || !targetSide.subscriptionExists(silo)) {
            throw new TenantCutoverException("a SYNCING cutover row exists for organization " + orgId
                    + " but its replication objects do not (publication on '" + source
                    + "': " + sourceSide.publicationExists(silo) + ", subscription on '" + target
                    + "': " + targetSide.subscriptionExists(silo) + ") — a build died between the row"
                    + " and the streams. abort() cleans it up; then re-run. Nothing was done.");
        }
        log.info("resuming the SYNCING cutover of tenant {} to '{}' — streams found live", orgId, target);
        return true;
    }

    private Map<String, Fingerprint> verifyBothSides(JdbcTemplate sourceDb, JdbcTemplate targetDb,
            String silo, UUID orgId, String source, String target) {
        Plan sourcePlan = tables.planFor(sourceDb, silo);
        Plan targetPlan = tables.planFor(targetDb, silo);
        rows.assertSameShape(sourcePlan, targetPlan);
        Map<String, Fingerprint> before = rows.fingerprint(sourceDb, sourcePlan, orgId);
        Map<String, Fingerprint> after = rows.fingerprint(targetDb, targetPlan, orgId);
        List<String> differences = new ArrayList<>();
        before.forEach((table, expected) -> {
            Fingerprint actual = after.get(table);
            if (!expected.matches(actual)) {
                differences.add(table + ": '" + source + "' has " + expected + ", '" + target
                        + "' has " + actual);
            }
        });
        if (!differences.isEmpty()) {
            throw new TenantCutoverException("the replicated copy of organization " + orgId + " does"
                    + " not match its source, so NOTHING was flipped and the tenant is still served"
                    + " from '" + source + "'. Differences: " + differences + ". The drain proved the"
                    + " slot crossed the captured LSN, so a difference here means a writer got past the"
                    + " freeze — find it before retrying (the stream is still running; a retry costs"
                    + " only a new freeze).");
        }
        return before;
    }

    private void flip(UUID orgId, String silo, String source, String target) {
        // One transaction for the two registry writes: no reader may ever see the placement flipped
        // beside a cutover row still claiming SYNCING, or the recovery story needs a fourth state.
        platform(() -> transactions.execute(status -> {
            if (!placements.completeDatasourceMove(orgId, silo, source, target)) {
                throw new TenantCutoverException("the placement flip for organization " + orgId
                        + " matched no row: expected PROVISIONING in '" + silo + "' on '" + source
                        + "'. Nothing has moved — the destination holds a verified copy the streams"
                        + " keep current; abort() or retry.");
            }
            if (!cutovers.markCut(orgId)) {
                throw new TenantCutoverException("the cutover row for organization " + orgId
                        + " was not SYNCING at the flip — the whole flip was rolled back and the"
                        + " tenant is still served from '" + source + "'.");
            }
            return null;
        }));
    }

    private void evict(UUID orgId, List<String> warnings) {
        try {
            // Includes TenantRoutes.forget, whose Route now carries the datasource half — the memo
            // this flip actually changed.
            caches.evictAfterPlacementFlip(orgId);
        } catch (RuntimeException eviction) {
            warnings.add("cache eviction failed after the flip (" + eviction + ") — this process may"
                    + " still answer from values resolved against the old database; restart the pods");
            log.error("tenant {} was cut over but its caches did not clear", orgId, eviction);
        }
    }

    /**
     * The promoter's post-flip wait, for the identical arithmetic: the flip is one row and nothing
     * pushes it, so every other pod addresses this tenant's borrows to the OLD pool for up to a route
     * TTL. Survivable exactly while the freeze holds and the source rows still exist — and a cutover
     * keeps the source rows for the whole watch window, so the wait here protects the WRITE half
     * only. A wait and not a broadcast, for the promoter's stated reason.
     */
    private void holdOneRouteTtl(UUID orgId, String from, String to) {
        Duration ttl = TenantRoutes.routeTtl();
        log.info("tenant {} is flipped to '{}'; holding the freeze {} more so every process drops its"
                + " memoized route to '{}' (ADR 0011 §7.2 step 8)", orgId, to, ttl, from);
        try {
            Thread.sleep(ttl.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new TenantCutoverException("interrupted while holding the freeze for the route TTL"
                    + " after organization " + orgId + " flipped to '" + to + "'. THE FLIP STANDS —"
                    + " wait out " + ttl + " before assuming every pod routes to the new database,"
                    + " and the reverse stream has NOT been established: runbook, 'stuck at CUT'.",
                    interrupted);
        }
    }

    private void claimForTheWindow(UUID orgId, String silo, UUID window) {
        boolean claimed;
        try {
            claimed = platform(() -> placements.beginCutover(orgId, silo));
        } catch (RuntimeException failure) {
            closeHttpFreeze(orgId, window, new ArrayList<>());
            platform(() -> freezes.thaw(orgId));
            throw failure;
        }
        if (!claimed) {
            closeHttpFreeze(orgId, window, new ArrayList<>());
            platform(() -> freezes.thaw(orgId));
            throw new TenantCutoverException("organization " + orgId + " is no longer simply ACTIVE in"
                    + " '" + silo + "' — something moved it since the sync began. Both freezes have"
                    + " been lifted and nothing was flipped."
                    + " `select * from platform.tenant_placement where org_id = '" + orgId + "'`.");
        }
    }

    private UUID openHttpFreeze(UUID orgId, Instant until, String from, String to) {
        HttpWriteFreeze freeze = httpFreeze.getIfAvailable();
        if (freeze == null) {
            platform(() -> freezes.thaw(orgId));
            throw new TenantCutoverException("no HttpWriteFreeze implementation is registered, so org"
                    + " writes over HTTP would keep landing while the frozen tail drained. That is the"
                    + " half of the freeze ADR 0010 §6 names FIRST; a cutover without it flips a copy"
                    + " requests are still writing to.");
        }
        try {
            // On the org's own axis: the window row is tenant-tier, lands in the silo, and REPLICATES
            // — so the destination arrives already carrying the very window that is gating it, and
            // the gate is continuous across the flip exactly as it is across a promotion's.
            return TenantContext.callAs(orgId, () -> freeze.open(orgId, until,
                    "Moving this organization's data between databases (" + from + " -> " + to
                            + "). Reads are unaffected; writes resume automatically."));
        } catch (RuntimeException failure) {
            platform(() -> freezes.thaw(orgId));
            throw failure;
        }
    }

    private void closeHttpFreeze(UUID orgId, UUID window, List<String> warnings) {
        if (window == null) {
            return;
        }
        try {
            // After the flip "the org's axis" resolves to the DESTINATION database — where the
            // replicated window row is. Before it, the source. The axis follows the row on both
            // paths, which is what makes one call correct on both.
            TenantContext.runAs(orgId, () -> httpFreeze.getObject().close(orgId, window));
        } catch (RuntimeException failure) {
            warnings.add("the RESTRICT maintenance window " + window + " could not be cancelled ("
                    + failure + "); this organization's writes stay blocked until it expires or an"
                    + " operator cancels it: DELETE /api/v1/orgs/" + orgId + "/maintenance/" + window);
            log.error("tenant {} could not have its cutover freeze window cancelled", orgId, failure);
        }
    }

    private TenantPlacement servingPlacement(UUID orgId) {
        if (orgId == null) {
            throw new IllegalArgumentException("a cutover names an organization; null names nothing");
        }
        TenantPlacement placement = platform(() -> placements.find(orgId))
                .orElseThrow(() -> new TenantCutoverException("organization " + orgId + " has no row in"
                        + " platform.tenant_placement — a tenant with no recorded home is not one this"
                        + " can move (ADR 0010 §4.3)."));
        if (!placement.isActive()) {
            throw new TenantCutoverException("organization " + orgId + " is " + placement.state()
                    + " in '" + placement.schemaName() + "', not ACTIVE. Only a serving tenant can cut"
                    + " over. `select * from platform.tenant_freeze where org_id = '" + orgId + "'`"
                    + " says whether a promotion or another cutover's window still holds it.");
        }
        return placement;
    }

    private static String requireSiloTenant(UUID orgId, TenantPlacement placement) {
        if (TenantSchemas.TENANT_POOL.equals(placement.schemaName())) {
            throw new TenantCutoverException("organization " + orgId + " is pooled, and only a silo"
                    + " can cut over: a row-filtered publication breaks every pooled tenant's UPDATE"
                    + " and DELETE under PK replica identity (ADR 0011 §7.1, measured), so the hop"
                    + " discipline is 0->1->2 — promote it first (docs/runbooks/tenant-promotion.md).");
        }
        String silo = TenantSchemas.requireSiloSchema(placement.schemaName());
        String expected = TenantSchemas.siloSchema(orgId);
        if (!expected.equals(silo)) {
            throw new TenantCutoverException("platform.tenant_placement names schema '" + silo
                    + "' for organization " + orgId + ", but that tenant's silo can only be '"
                    + expected + "' (ADR 0010 §3.1) — publishing the recorded name would move another"
                    + " tenant's schema.");
        }
        return silo;
    }

    private void requireLogicalWal(JdbcTemplate db, String datasourceName) {
        String level = db.queryForObject("show wal_level", String.class);
        if (!"logical".equals(level)) {
            throw new TenantCutoverException("datasource '" + datasourceName + "' runs wal_level="
                    + level + ", and logical replication needs 'logical' — set at ADR 0010 Phase 0 for"
                    + " the platform (docker/docker-compose.yml and the k3s statefulset carry the"
                    + " reasoning; changing it needs a RESTART, docs/PRODUCTION.md). Nothing was done.");
        }
    }

    private List<String> inFlightSchemas() {
        return platform(() -> cutovers.inFlight()).stream().map(Cutover::schemaName).toList();
    }

    private Cutover requireCutover(UUID orgId) {
        return platform(() -> cutovers.find(orgId))
                .orElseThrow(() -> new TenantCutoverException("no cutover is recorded for organization "
                        + orgId + " — nothing to act on."
                        + " `select * from platform.tenant_cutover order by started_at`."));
    }

    /**
     * Polls until {@code done}, failing at {@code deadline} with a message that says what was being
     * waited for and that the wait is resumable. The predicate may throw (a missing slot is an
     * answer, not a timeout) and that failure propagates as itself.
     */
    private static void waitFor(String what, Instant deadline, BooleanSupplier done) {
        while (!done.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new TenantCutoverException("timed out waiting for " + what + ". The stream is"
                        + " still running and nothing has been flipped; fix what is slow (an inactive"
                        + " slot? `select * from pg_replication_slots`) and re-run — the wait resumes.");
            }
            try {
                Thread.sleep(POLL.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new TenantCutoverException("interrupted while waiting for " + what
                        + "; nothing has been flipped", interrupted);
            }
        }
    }

    /**
     * <strong>The freeze that must still be in hand when the flip is decided</strong>: the route-TTL
     * hold every process needs to drop its memoized route, PLUS the work that happens after the hold
     * and before the unfreeze — establishing the reverse stream on the way out, tearing the streams
     * down on the way back. Both paths check against this and neither may spend more than it reserved.
     */
    private static Duration frozenTail() {
        return TenantRoutes.routeTtl().plus(REVERSE_STREAM_BUDGET);
    }

    /** The frozen drain's own deadline: the freeze minus the tail it must leave room for, minus slack. */
    private Instant drainBy(Instant freezeDeadline) {
        return freezeDeadline.minus(frozenTail()).minus(VERIFY_HEADROOM);
    }

    private void requireTimeLeft(UUID orgId, Instant deadline, Duration headroom, String what) {
        if (Instant.now().plus(headroom).isBefore(deadline)) {
            return;
        }
        throw new TenantCutoverException("the freeze on organization " + orgId + " lapses at "
                + deadline + ": " + what + ". Background writers resume the moment it expires, so"
                + " continuing would flip onto a copy that may already be stale.");
    }

    private void settle() {
        try {
            Thread.sleep(SETTLE.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new TenantCutoverException("interrupted while waiting for in-flight requests to"
                    + " finish; nothing has been flipped", interrupted);
        }
    }

    /**
     * Registry reads and writes run on the PLATFORM axis explicitly, never on the ambient one: after
     * the flip, this tenant's org axis borrows from the DESTINATION pool, whose minimal platform
     * schema holds none of these tables — §5.1's tripwire, which this class must not trip on itself.
     * One overload only: a Runnable twin would be ambiguous for every value-returning lambda body.
     */
    private static <T> T platform(java.util.function.Supplier<T> work) {
        return TenantContext.callAsPlatform(work);
    }

    private static void quietly(String what, Runnable teardown) {
        try {
            teardown.run();
        } catch (RuntimeException failure) {
            log.warn("while unwinding a failed cutover, the {} could not be removed: {}", what,
                    failure.getMessage());
        }
    }

    /**
     * Runs one teardown step and, when it fails, records it instead of abandoning the rest — an
     * unreachable database must not leave the reachable one's objects behind. The list it appends to
     * is what the caller RETURNS, never only what it logs.
     */
    private static void attempt(List<String> leftovers, String what, Runnable teardown) {
        try {
            teardown.run();
        } catch (RuntimeException failure) {
            log.error("could not complete the {}: {}", what, failure.getMessage());
            leftovers.add(what + " (" + failure.getMessage() + ")");
        }
    }

    private static void refuseInsideATransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("a cutover must run OUTSIDE a transaction: it runs Flyway"
                    + " and CREATE SUBSCRIPTION (which Postgres refuses in a transaction block), needs"
                    + " its verification in transactions of its own, and pins tenant axes — which"
                    + " TenantContext refuses inside one.");
        }
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static String holder() {
        String host = Optional.ofNullable(System.getenv("HOSTNAME")).orElse("unknown-host");
        return TenantCutover.class.getSimpleName() + "@" + host + "/" + ProcessHandle.current().pid();
    }

    private static String describe(Throwable failure) {
        return failure.getClass().getSimpleName() + ": " + failure.getMessage();
    }
}
