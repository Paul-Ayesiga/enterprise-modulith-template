package ug.co.smsone.shared.tenancy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.tenancy.placement.PlacementState;
import ug.co.smsone.shared.tenancy.placement.TenantPlacement;
import ug.co.smsone.shared.tenancy.placement.TenantPlacements;
import ug.co.smsone.shared.tenancy.promotion.TenantFreezes;

/**
 * Where the fleet lives right now, as one snapshot: the homes a tenant-axis sweep must visit, and the
 * tenants it must keep its hands off (ADR 0010 §3.4, §5, Phase 5).
 *
 * <h2>The failure this exists to remove</h2>
 *
 * <p>Every background sweep in this codebase used to be one statement against {@code tenant_pool},
 * because every tenant was in {@code tenant_pool}. The moment one organization is promoted to
 * {@code t_<32hex>}, that statement stops doing that organization's work — and <strong>nothing
 * fails</strong>. No exception, no log line, no metric moves: the sweep visits a schema the tenant is
 * no longer in, finds nothing of theirs, and reports a healthy run. SLA breaches stop escalating for
 * the promoted tenant, its soft-deleted rows are never hard-deleted, its retention policy stops being
 * enforced. That is the worst shape a failure can take, and it is the DEFAULT outcome of promotion
 * unless every one of those sweeps asks this class where to go.
 *
 * <h2>What "bounded" means here, precisely</h2>
 *
 * <p>ADR 0010 §5.1 and §8 Q1 measured the cost of the obvious implementation — one
 * {@code UNION ALL} branch per schema — at 0.5–0.66 ms of planning per branch that <em>does not
 * amortize</em>, and 2.004 lock entries per branch against roughly 6,400 cluster-wide slots, which puts
 * a hard wall at ~3,200 branches. This class does not build that statement and neither does any of its
 * callers. The fan-out is <strong>one statement (or one transaction) per home</strong>, so:
 *
 * <ul>
 *   <li>lock entries never accumulate — each home's locks are released with its own statement, so the
 *       6,400-slot ceiling is never approached however many homes there are;</li>
 *   <li>the planner sees the same small query it sees today, once per home, and every one of them is
 *       cacheable — where a 200-branch UNION's ~730 KB of query text defeats both the JDBC statement
 *       cache and Postgres' plan cache;</li>
 *   <li>the bound that keeps a run inside its ShedLock lease is TIME, not branch count, and it is
 *       enforced by {@code TenantHomeSweep}'s per-home deadline plus its resumable cursor.</li>
 * </ul>
 *
 * <p>The cost traded for that is one round trip per home instead of one for all of them. At the ADR's
 * 200-silo ceiling that is 201 round trips per nightly sweep, which is nothing, and 201 per minute for
 * {@code SlaEscalationJob}, which is the number §5.2 says must eventually become
 * {@code platform.ticket_index} instead. {@link #SILO_CEILING} is where that "eventually" is written
 * down.
 *
 * <h2>Frozen tenants: the promotion freeze, answered at the granularity a sweep has</h2>
 *
 * <p>A promotion copies a tenant's rows out of {@code tenant_pool} and then flips its placement. A job
 * that writes to that tenant while the copy is in flight either loses the write — an {@code UPDATE} the
 * copy has already read past, which row-count verification cannot see — or aborts the promotion, when
 * an {@code INSERT} or {@code DELETE} moves a count. ADR 0010 §6 hop 0→1 says this in one sentence: the
 * HTTP freeze "gates HTTP org paths only", and the workers and purges "must be paused for that org too".
 *
 * <p>{@link TenantFreezes} is that pause and this class is where the fan-out consults it. Two reads,
 * once per run, and they answer two different questions:
 *
 * <ul>
 *   <li><strong>{@code tenant_freeze}</strong> — is a promoter holding this tenant <em>right now</em>?
 *       It expires on its own, so a promoter that died does not stop a tenant's retention forever.</li>
 *   <li><strong>{@code tenant_placement.state = PROVISIONING}</strong> — is a relocation recorded as
 *       in-flight? {@code TenantPromoter} takes the freeze and then calls
 *       {@code TenantPlacements.beginRelocation}, so this covers the window between the two and the
 *       case where a freeze lapsed under a copy that is still running.</li>
 * </ul>
 *
 * <p>Both are consulted because they fail in opposite directions: the freeze is authoritative and
 * bounded, the placement state is durable and would otherwise be unbounded, and a sweep that trusted
 * either alone would have a window in which it wrote into a schema being moved.
 *
 * <p><strong>The second read is the one with no natural deadline, so it is given two.</strong>
 * {@code tenant_placement} has no expiry, so a pod killed mid-provision leaves a PROVISIONING row
 * forever — and read naively that one row stands the whole fleet's background work down, silently, on
 * every run, for as long as nobody notices. {@link #aLiveRelocationHoldsThePool} is where that is
 * bounded: only a row naming {@code tenant_pool} can pause the pool at all, and only while it is
 * younger than {@link #PROVISIONING_STANDS_DOWN_FOR}. Past that it is reported at ERROR and the pool is
 * swept anyway.
 *
 * <p><strong>The pool is stood down whole, and that is the honest enforcement rather than a shortcut.</strong>
 * The fan-out's unit is a SCHEMA and the freeze's unit is a TENANT. A pool-wide sweep has no way to say
 * "every org but this one" without threading an exclusion list through every repository query in eight
 * modules — so while a frozen tenant is still living in {@code tenant_pool}, nothing sweeps that schema.
 * It is affordable precisely because every consumer is resumable: the pool is deferred, not skipped, and
 * the next run is sixty seconds away for {@code SlaEscalationJob} and one night against a thirty-day
 * window for the purges. A frozen tenant that has already been flipped into its silo does NOT stand the
 * pool down — only its own home drops out.
 *
 * <p>{@link Fleet} then answers in the two shapes the callers have: a sweep over HOMES gets a list with
 * the frozen and half-built ones already removed, and a sweep over ORGANIZATIONS —
 * {@code UsageExportJob} is the one — asks {@link Fleet#homeOf} and gets an empty answer for a tenant it
 * must not touch this pass.
 */
@Component
public class TenantFanOut {

    private static final Logger log = LoggerFactory.getLogger(TenantFanOut.class);

    /**
     * ADR 0010 §8 Q1's recommended default, written down where the fan-out can see it. It is
     * <strong>not</strong> enforced here and must not be: truncating the home list would silently stop
     * doing the 201st tenant's work, which is the exact bug this whole class exists to remove. The
     * promoter is where a ceiling is enforced, because refusing to CREATE the 201st silo is a decision
     * with an operator behind it; dropping it from a nightly sweep is not.
     *
     * <p>What crossing it does here is log, once per snapshot, at WARN — because §5.1's real trigger is
     * lower still: <em>at 50 silos</em> the platform ticket queue is supposed to stop fanning out and
     * start reading {@code platform.ticket_index}. A fleet past this line is a fleet whose fan-out
     * shape is the next piece of work, and the only way that fact reaches anyone is if something says
     * it.
     */
    public static final int SILO_CEILING = 200;

    /**
     * How long a {@code PROVISIONING} placement naming {@code tenant_pool} may stand the pool down
     * before this class stops believing it and says so at ERROR.
     *
     * <p><strong>Four times the shipped {@code app.tenancy.promotion.freeze} of PT30M.</strong> A
     * promotion cannot legitimately run past its own freeze — {@code TenantPromoter.requireTimeLeft}
     * aborts rather than flipping onto a copy the freeze no longer protects — so two hours is not a
     * guess about how long a promotion takes, it is a multiple of the longest one the shipped
     * configuration permits. <strong>An installation that raises that budget above this number must
     * raise this too</strong>, and the ERROR line is what will tell it: a real promotion crossing this
     * line logs as wreckage, which is a false alarm, where the reverse mistake is the fleet-wide silent
     * stand-down this bound exists to remove. The number is compiled in rather than injected because
     * {@code TenantPromotionProperties} is package-private to {@code shared.tenancy.promotion} and
     * widening it to expose one duration would put a knob on the wrong class.
     */
    public static final Duration PROVISIONING_STANDS_DOWN_FOR = Duration.ofHours(2);

    private final TenantPlacements placements;
    private final TenantFreezes freezes;

    TenantFanOut(TenantPlacements placements, TenantFreezes freezes) {
        this.placements = placements;
        this.freezes = freezes;
    }

    /**
     * Reads the registry once and freezes the answer for the run.
     *
     * <p><strong>Once per run, never once per home.</strong> A sweep that re-read the registry between
     * homes could visit a tenant twice or skip one, and — worse — could disagree with itself about
     * whether a home exists half way through a pass whose cursor is an index into that list.
     *
     * <p>Pinned {@code PLATFORM} rather than left on whatever axis the caller had. Every statement in
     * {@link TenantPlacements} names {@code platform.} and so resolves from any axis, including none —
     * but a sweep calls this BEFORE it pins its first home, so without the pin the borrow lands on
     * {@code no_tenant}, and the next person to add an unqualified table to that class would get a
     * failure with nothing in it pointing here.
     */
    public Fleet fleet() {
        return TenantContext.callAsPlatform(() -> {
            Set<UUID> frozen = freezes.inForce().stream()
                    .map(TenantFreezes.Freeze::orgId)
                    .collect(Collectors.toUnmodifiableSet());
            // ONE read of every row the registry has NOT settled — PROVISIONING and FAILED alike —
            // answering two different questions. Whether a relocation stands the whole POOL down is the
            // first (see the class note on why both reads are taken, and on why this one is bounded
            // twice); which individual TENANTS have no settled home this pass is the second, and under
            // silo-per-org that is every signup that is in flight right now — see Fleet.homeOf.
            List<TenantPlacement> unsettledPlacements = placements.unsettled();
            boolean relocating = aLiveRelocationHoldsThePool(unsettledPlacements);
            Set<UUID> unsettled = unsettledPlacements.stream()
                    .filter(TenantFanOut::hasNoSettledHome)
                    .map(TenantPlacement::orgId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            // ACTIVE is not on its own a promise that the schema is THERE — see aHomeNobodyBuilt. The
            // tenants it drops join `unsettled`, so Fleet.homeOf refuses them too rather than letting
            // them fall through to the pool, which is not where the router is sending them either.
            List<TenantPlacement> silos = new ArrayList<>(placements.activeSilos());
            silos.removeIf(silo -> {
                if (aHomeNobodyBuilt(silo)) {
                    unsettled.add(silo.orgId());
                    return true;
                }
                return false;
            });
            if (silos.size() > SILO_CEILING) {
                log.warn("The fleet holds {} silos, past ADR 0010 §8 Q1's ceiling of {}. Every cross-tenant"
                                + " sweep now costs that many round trips per run, and the platform ticket"
                                + " queue costs them per PAGE — §5.1's answer past 50 is"
                                + " platform.ticket_index, not a wider fan-out.",
                        silos.size(), SILO_CEILING);
            }
            return new Fleet(silos, frozen, unsettled, relocating);
        });
    }

    /**
     * <strong>Does a PROVISIONING placement stand {@code tenant_pool} down this run?</strong> Two
     * narrowings, and both exist because this is the only pause in the design that a crashed process can
     * make permanent — {@code tenant_placement} has no expiry, unlike {@code tenant_freeze}.
     *
     * <p>The failure being removed: a pod killed between {@code reserve} and {@code announce}, or
     * between {@code beginRelocation} and the flip, leaves a PROVISIONING row forever. Read as "any
     * PROVISIONING row anywhere", that one corpse takes {@code tenant_pool} out of {@link Fleet#homes()}
     * on every run for every consumer — soft-delete purge, SLA escalation, trial expiry, dunning,
     * webhook retention, exchange retention, device-trust revocation — for the schema holding every
     * unpromoted tenant in the fleet. Silently, and with no deadline.
     *
     * <ol>
     *   <li><strong>Only a row that names the POOL.</strong> A PROVISIONING row's {@code schema_name} is
     *       the schema whose contents are unsettled: for a promotion it is the SOURCE the rows are still
     *       in ({@code beginRelocation} moves the state and leaves the name alone), and for a signup or
     *       a demotion it is the silo being built. So a half-made silo has nothing to do with the pool,
     *       and standing the pool down for it was always over-broad. Under any policy that provisions a
     *       schema per signup, this alone is the difference between "every concurrent signup pauses the
     *       fleet" and "no signup ever does".</li>
     *   <li><strong>Only while it is plausibly alive.</strong> A promotion cannot legitimately outlive
     *       its own freeze — {@code TenantPromoter} refuses to flip once the deadline is gone — so a row
     *       older than {@link #PROVISIONING_STANDS_DOWN_FOR} is not a promotion in progress, it is
     *       wreckage. Past that line the pool is swept again and the row is reported at ERROR on every
     *       run until somebody fixes the registry. Standing retention, escalation and billing down for
     *       the whole fleet is a far worse failure than the one it would prevent, and it is exactly the
     *       trade this class already makes for {@link PlacementState#FAILED}.</li>
     * </ol>
     *
     * <p>The tenant-level protection is unaffected by either narrowing: a promoter holds
     * {@code platform.tenant_freeze} for the duration, that row carries a deadline, and
     * {@link Fleet#homeOf} refuses a frozen tenant whatever this method decides.
     *
     * <p><strong>A DEMOTION is covered by the freeze rather than by this method, and deliberately.</strong>
     * Its PROVISIONING row names the silo it is copying OUT of, so narrowing (1) does not pause the pool
     * — but the registry records no DESTINATION, so there is nothing here that could. What does pause it
     * is {@code frozenTenantStillPooled}: a demoting tenant is frozen and is not in
     * {@link TenantPlacements#activeSilos()} while it is PROVISIONING, so it counts as a frozen tenant
     * that is not siloed and the pool stands down for it. The residual case is a demotion whose freeze
     * has LAPSED while its copy is still running, where a purge could delete rows the copy has just
     * written into the pool. That promotion is already doomed — {@code requireTimeLeft} aborts before
     * the flip once the deadline is gone, and the abandoned destination rows are the operator's to
     * reclaim — so the cost is duplicate rows nobody was going to keep, against a fleet-wide sweep
     * outage with no deadline.
     */
    private boolean aLiveRelocationHoldsThePool(List<TenantPlacement> unsettled) {
        Instant tooOld = Instant.now().minus(PROVISIONING_STANDS_DOWN_FOR);
        boolean holds = false;
        for (TenantPlacement placement : unsettled) {
            if (placement.state() != PlacementState.PROVISIONING) {
                // FAILED never pauses anything — see Fleet.poolPaused's last paragraph. The argument is
                // sharper now that this method is handed the FAILED rows too: TenantMigrationRunner's
                // FAIL_PLACEMENT is `where schema_name = ?` with no state arm, so ONE failed pass over
                // tenant_pool marks EVERY pooled tenant FAILED at once. Counted here, that single bad
                // migration would stand the whole fleet's background work down until somebody repaired
                // the registry by hand.
                continue;
            }
            if (!TenantSchemas.TENANT_POOL.equals(placement.schemaName())) {
                continue; // a silo being built or torn down; the pool is nothing to do with it
            }
            if (placement.updatedAt().isAfter(tooOld)) {
                holds = true;
                continue;
            }
            log.error("tenant {} has been PROVISIONING in {} since {}, which is past {} — that is a"
                            + " promotion that died, not one that is running. The pool is being swept"
                            + " anyway, because standing every background job down for the whole fleet"
                            + " indefinitely is worse than sweeping around one broken tenant. FIX THE"
                            + " REGISTRY: docs/runbooks/tenant-promotion.md, recovery section.",
                    placement.orgId(), placement.schemaName(), placement.updatedAt(),
                    PROVISIONING_STANDS_DOWN_FOR);
        }
        return holds;
    }

    /**
     * <strong>Does this unsettled row mean "this run must not touch that tenant", or only "its home is
     * the pool and the pool is fine"?</strong> The two are not the same question and answering them
     * together is how a serving tenant's work ends up in another schema.
     *
     * <ul>
     *   <li><strong>PROVISIONING, wherever it points: no home this pass.</strong> The rows are being
     *       copied (a promotion's source), or the schema named is still being built (a silo signup, or a
     *       demotion's destination). Either way the contents are moving and a write from here is lost or
     *       aborts the copy.</li>
     *   <li><strong>FAILED naming a SILO: no home this pass.</strong> {@link TenantPlacements#activeSilos()}
     *       already refuses to SWEEP it — "a home nothing has proved fit" — and answering
     *       {@code tenant_pool} for the same tenant one method later undoes that refusal in the worst
     *       possible way, because {@code TenantRoutes} is still routing it to {@code t_<hex>}. This is
     *       not a hypothetical shape: {@code TenantMigrationRunner}'s FAIL_PLACEMENT is
     *       {@code where schema_name = ?} with no state arm, so one failed migration pass over a silo
     *       marks a fully ACTIVE, serving, ROW-BEARING tenant FAILED — and {@code UsageExportJob} would
     *       then look for its billing account in the pool, find none, and file it as unbillable every
     *       night until somebody noticed the money.</li>
     *   <li><strong>FAILED naming the POOL: the pool, as before.</strong> Its rows really are there and
     *       the router agrees, so refusing would defer that tenant's work for as long as the row stood —
     *       and via the same FAIL_PLACEMENT statement one bad pool migration would do it to the whole
     *       fleet at once. ADR 0010 §8 Q7's rule (serve the tenant, page a human) points the same way.</li>
     * </ul>
     */
    private static boolean hasNoSettledHome(TenantPlacement placement) {
        return placement.state() == PlacementState.PROVISIONING
                || !TenantSchemas.TENANT_POOL.equals(placement.schemaName());
    }

    /**
     * <strong>An ACTIVE row naming a silo that has no recorded version is a tenant announced into a
     * schema nobody ever built, and handing it to a sweep costs every OTHER home its run.</strong>
     *
     * <p>{@link PlacementState#ACTIVE}'s own definition is three facts at once — "the schema exists, is
     * at {@code schema_version}, and the tenant has been announced" — and exactly one write in the
     * system can produce a row that says ACTIVE while the second fact is missing:
     * {@link TenantPlacements#announce}'s INSERT arm takes {@code schema_version} from a scalar subquery
     * over the tenants already living in that schema, and a silo has none. Every other path stamps a
     * version first — {@code TenantProvisioner.provisionHomeFor} calls {@code recordMigrated} before it
     * returns, {@code completeRelocation} writes the version it verified the copy against, and
     * {@code TenantMigrationRunner} writes one on its success AND its failure path. So a null here is
     * not "the version is unknown"; it is "nothing has ever migrated this schema", which under
     * {@code silo-per-org} means an announcement that ran without the provisioning that must precede it
     * (ADR 0010 §4.3).
     *
     * <p><strong>Why this class has to refuse it rather than let the sweep find out.</strong> Postgres
     * does not fail a {@code SET search_path} naming a schema that does not exist — it resolves to
     * nothing — so the first unqualified tenant table in the visit dies with
     * {@code relation "org_group" does not exist}, and the fan-out's own doctrine then reports that as
     * the run's failure. One registry row therefore fails the nightly purge, the trial expiry, the
     * dunning pass, the webhook retention and the SLA escalation, EVERY run, with a message naming a
     * schema that belongs to a tenant nobody was asking about. The work of the other homes still
     * happens — {@code TenantHomeSweep} isolates per home — so what is actually lost is the signal: a
     * job that fails every night is a job whose real failures nobody sees.
     *
     * <p>Reported at ERROR on every run and never repaired from here, the same trade
     * {@link #aLiveRelocationHoldsThePool} makes for a PROVISIONING row that outlived its promotion:
     * the registry is the operator's to fix, and a fan-out that quietly rewrote it would be deciding
     * where a tenant lives.
     *
     * <p><strong>It is not counted in {@link Fleet#withheldHomes()}, deliberately.</strong> That counter
     * exists so a caller with no next pass ({@code DeviceTrustRevocationListener}) can refuse to report
     * an incomplete sweep as done, and it does that by throwing so the outbox retries. A home a
     * promotion is holding will exist in a minute; a home nobody built will not exist next time either,
     * so retrying forever would turn one broken registry row into a permanently stuck publication.
     * There are no rows in a schema that does not exist, so nothing is being skipped — only reported.
     */
    private static boolean aHomeNobodyBuilt(TenantPlacement silo) {
        if (silo.schemaVersion() != null) {
            return false;
        }
        log.error("tenant {} is recorded ACTIVE in schema {}, but no version has ever been recorded"
                        + " against that schema — nothing migrated it, so it is a tenant that was"
                        + " ANNOUNCED without being PROVISIONED (ADR 0010 §4.3). It is being left out of"
                        + " this run's homes: swept, that schema fails on its first unqualified table and"
                        + " takes the run's report down with it for every other tenant. FIX THE REGISTRY:"
                        + " provision the schema and re-record it, or delete the placement row.",
                silo.orgId(), silo.schemaName());
        return true;
    }

    /**
     * One run's view of the fleet. Immutable: it is read once and then walked, so nothing a concurrent
     * promotion does can move a home out from under a cursor mid-pass.
     */
    public static final class Fleet {

        private final List<TenantHome> homes;
        private final Map<UUID, TenantHome> siloByOrg;
        private final Set<UUID> frozen;
        private final Set<UUID> unsettled;
        private final boolean poolPaused;
        private final int siloCount;

        private final int withheldHomes;

        private Fleet(List<TenantPlacement> activeSilos, Set<UUID> frozen, Set<UUID> unsettled,
                boolean relocating) {
            // EVERY tenant that is serving from a silo, taken BEFORE the frozen ones are dropped from
            // the sweep list below. The two sets answer different questions and reading one off the
            // other is the bug this line replaced: `byOrg` is "which silos does this run VISIT", which
            // has the frozen ones subtracted, while what decides whether the POOL stands down is "does
            // this frozen tenant still LIVE in the pool" — a question about placement, not about the
            // itinerary. Derived from `byOrg.keySet()`, a frozen tenant was never in it, so EVERY
            // freeze read as a frozen tenant still in the pool and one unthawed freeze on one long-since
            // siloed tenant stood the whole fleet's background work down.
            Set<UUID> siloedOrgs = activeSilos.stream()
                    .map(TenantPlacement::orgId)
                    .collect(Collectors.toUnmodifiableSet());
            Map<UUID, TenantHome> byOrg = new LinkedHashMap<>();
            for (TenantPlacement placement : activeSilos) {
                // A frozen tenant that already lives in its silo loses only its own home: nothing else
                // shares that schema, so there is no reason to stand anything else down for it.
                if (frozen.contains(placement.orgId())) {
                    continue;
                }
                byOrg.put(placement.orgId(), TenantHome.silo(placement.orgId(), placement.schemaName()));
            }
            // The pool stands down for a frozen tenant that is NOT already siloed — which is every
            // tenant mid-promotion, since a promotion freezes the tenant while its rows are still in the
            // pool. `relocating` covers the same window from the registry's side.
            boolean frozenTenantStillPooled = frozen.stream().anyMatch(org -> !siloedOrgs.contains(org));
            this.poolPaused = relocating || frozenTenantStillPooled;

            List<TenantHome> ordered = new ArrayList<>();
            if (!poolPaused) {
                // The pool first, always. It holds every tenant nobody has promoted — 5,000 against a
                // ceiling of 200 — so a rotation that could leave it until last would starve the fleet
                // to serve the exception. TenantHomeSweep gives it its own budget for the same reason.
                ordered.add(TenantHome.pool());
            }
            ordered.addAll(byOrg.values());
            this.homes = List.copyOf(ordered);
            this.siloByOrg = Map.copyOf(byOrg);
            this.frozen = Set.copyOf(frozen);
            this.unsettled = Set.copyOf(unsettled);
            this.siloCount = byOrg.size();
            this.withheldHomes = (poolPaused ? 1 : 0) + (activeSilos.size() - byOrg.size());
        }

        /**
         * Every home a tenant-axis sweep must visit this run, pool first and silos in a stable order.
         *
         * <p>Stable ordering is not cosmetic: {@code TenantHomeSweep}'s resumable cursor remembers a
         * SCHEMA NAME, and a list whose order moved between runs would make "resume after t_ab…" mean a
         * different set of survivors every night. {@link TenantPlacements#activeSilos} orders by schema
         * name for exactly that.
         */
        public List<TenantHome> homes() {
            return homes;
        }

        /**
         * Where one organization's rows are, or empty when this run must not touch that tenant at all.
         *
         * <p><strong>The rule, and it is not a policy default.</strong>
         * This must answer with the home {@link TenantRoutes} is routing that tenant's live traffic
         * to, or refuse. Those are the only two safe answers, and the reason is that they are the two
         * failure modes: a home this disagrees with the router about is a sweep purging, escalating and
         * billing in a schema the tenant's own writes are not going to (ADR 0010 §1's worst failure,
         * arrived at from the background side), and a refusal is one pass of that tenant's work deferred.
         *
         * <p><strong>NO PLACEMENT ROW resolves to the pool, under BOTH policies.</strong>
         * Not because the pooled policy is the default — it is not, and this used to reason from
         * exactly that — but because {@code TenantRoutes.readHome} answers {@code tenant_pool} for a
         * tenant the registry has never heard of, whatever {@code app.tenancy.placement.policy} says. So
         * such a tenant's rows really are in the pool: it is a tenant created before the registry
         * existed, or one seeded around the placement path. Refusing it here would stop that tenant's
         * retention, escalation and billing <em>forever</em>, silently, which is the precise failure this
         * whole class was written to remove — and it would do so while the tenant went on writing rows
         * into the schema this refused to name.
         *
         * <p>Under {@code silo-per-org} a signup cannot LEAVE a tenant in that state either:
         * {@code TenantProvisioner.provisionHomeFor} reserves the row before it issues any DDL, so a
         * tenant whose provisioning died has a row that says so — which is the case below, not this one.
         *
         * <p><strong>A row that is NOT ACTIVE is a refusal, and silo-per-org made it the common case.</strong>
         * {@code PROVISIONING} means this tenant's home is not settled: for a promotion the rows are
         * being copied and a write from here would be lost or would abort the copy; for a signup under
         * {@code silo-per-org} the schema named by that row is still being built. It is the second that
         * changed — under {@code POOLED} a PROVISIONING row only ever came from a promotion, which also
         * holds a {@code tenant_freeze}, so {@link #frozen} caught it; under {@code silo-per-org} EVERY
         * signup passes through PROVISIONING and none of them is frozen. Left to fall through, those
         * tenants were answered {@code tenant_pool} while {@code TenantRoutes} was already routing them
         * to {@code t_<hex>} — the two disagreeing about a live tenant, on the common path.
         *
         * <p><strong>{@code FAILED} splits on the schema it names, and the split is the whole of it.</strong>
         * A FAILED row naming the POOL still resolves to the pool: those rows really are in the pool, the
         * router agrees, and {@code TenantMigrationRunner}'s FAIL_PLACEMENT is {@code where schema_name = ?}
         * with no state arm — so one failed pass over {@code tenant_pool} marks EVERY pooled tenant
         * FAILED, and refusing them would stand the whole fleet's per-tenant work down over one bad
         * migration. ADR 0010 §8 Q7 says the same thing about the tenant-facing side: serve, and page a
         * human. A FAILED row naming a SILO is refused, because the same statement can mark a fully
         * ACTIVE, serving, row-bearing siloed tenant FAILED — and {@link TenantPlacements#activeSilos()}
         * has already declined to sweep it, so answering {@code tenant_pool} for it one method later
         * would undo that refusal by pointing the work at another schema entirely. See
         * {@link TenantFanOut#hasNoSettledHome}.
         *
         * <p><strong>And an ACTIVE row is not on its own a promise that the schema is there.</strong> A
         * silo with no recorded {@code schema_version} is a tenant announced into a home nobody built;
         * {@link TenantFanOut#aHomeNobodyBuilt} drops it from the homes AND adds it here, because the
         * pool is no more its home than the schema that does not exist is.
         */
        public Optional<TenantHome> homeOf(UUID orgId) {
            if (frozen.contains(orgId)) {
                // Asked first, because a frozen tenant that has already been flipped into its silo is
                // still in activeSilos() — the freeze outlives the flip until the promoter thaws it.
                return Optional.empty();
            }
            if (unsettled.contains(orgId)) {
                // Before the silo lookup, not after: `activeSilos()` and `unsettled()` are two
                // statements, so a promotion or a failed migration that landed between them puts one
                // tenant in both answers. Refusing is the safe half of that disagreement — one deferred
                // pass, against a write into a schema whose contents are moving or unproven.
                return Optional.empty();
            }
            TenantHome silo = siloByOrg.get(orgId);
            if (silo != null) {
                return Optional.of(silo);
            }
            if (poolPaused) {
                // Everyone sharing a schema that stood down for somebody else's promotion. Their work is
                // deferred to the next run, which is the price of a freeze whose unit is a tenant and a
                // sweep whose unit is a schema.
                return Optional.empty();
            }
            return Optional.of(TenantHome.pool());
        }

        /** How many tenants are siloed — the number ADR 0010 §5.1's trigger and §8 Q1's ceiling are about. */
        public int siloCount() {
            return siloCount;
        }

        /**
         * How many homes a promotion is holding back this pass — zero on every ordinary day.
         *
         * <p>It exists for the callers that are <strong>not</strong> resumable. A nightly purge that
         * skips a frozen home simply purges it tomorrow and nobody needs to know; a one-shot reaction to
         * an event — {@code DeviceTrustRevocationListener} is the one — has no next pass, so a home it
         * could not enter is work that is never done. Those callers report this at ERROR rather than
         * dropping it silently, which is the only honest thing available: the alternative is writing
         * into a schema a promoter is copying, and the alternative to THAT is a revoked device left
         * trusted with nothing anywhere saying so.
         */
        public int withheldHomes() {
            return withheldHomes;
        }

        /**
         * True when {@code tenant_pool} is standing down this run because a promotion holds a tenant that
         * still lives in it.
         *
         * <p><strong>Why the whole pool and not just that tenant.</strong> The fan-out's unit is a
         * SCHEMA and the freeze's unit is a TENANT, and a pool-wide sweep has no way to express "every
         * org but this one" without threading an exclusion list through every repository query in eight
         * modules. Standing the home down for the duration is the honest enforcement of "a job must not
         * write to an org mid-promotion" at the granularity the mechanism actually has — and it is
         * cheap, because every sweep that consults this is resumable: the pool is not skipped, it is
         * deferred to the next run, which for {@code SlaEscalationJob} is sixty seconds and for the
         * nightly purges is one night against a thirty-day retention window.
         *
         * <p><strong>Under the shipped policy a signup can never trigger it.</strong>
         * {@code PlacementPolicy.POOLED} announces a new tenant straight to {@link PlacementState#ACTIVE}
         * — {@link TenantPlacements#reserve} is not on that path at all — so a PROVISIONING row means
         * provisioning machinery is running. Under the non-default {@code silo-per-org} policy each
         * signup does pass through PROVISIONING, and it still does not pause the pool: that row names
         * the silo being built, not {@code tenant_pool}, and
         * {@link TenantFanOut#aLiveRelocationHoldsThePool} only counts rows that name the pool.
         *
         * <p><strong>And a PROVISIONING row cannot pause anything indefinitely.</strong> It is believed
         * for {@link TenantFanOut#PROVISIONING_STANDS_DOWN_FOR} and then reported as wreckage — see that
         * constant. A pause with no deadline is the one thing this mechanism must not have, because its
         * symptom is every background job quietly not running for every unpromoted tenant.
         *
         * <p>{@link PlacementState#FAILED} deliberately does NOT pause anything. A failed provision can
         * sit in the registry for days (ADR 0010 §8 Q7 says to serve that tenant at its old version and
         * page), and standing retention down platform-wide for one broken tenant is a far worse failure
         * than the one it would prevent.
         */
        public boolean poolPaused() {
            return poolPaused;
        }
    }
}
