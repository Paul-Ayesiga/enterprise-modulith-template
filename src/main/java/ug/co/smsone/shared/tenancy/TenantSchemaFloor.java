package ug.co.smsone.shared.tenancy;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.persistence.TenantDataSources;
import ug.co.smsone.shared.tenancy.placement.TenantPlacement;
import ug.co.smsone.shared.tenancy.placement.TenantPlacements;

/**
 * The oldest tenant schema this binary is willing to talk to, and the memory that keeps asking cheap
 * (ADR 0010 §4.4).
 *
 * <h2>Why a floor exists at all</h2>
 *
 * <p>Every other version rule in this repo governs one axis: an old binary meeting a new schema, which
 * AGENTS §4.6 makes safe by insisting migrations are expand-only. Per-tenant migration adds a second
 * axis — <b>schema versus schema, at the same instant, with one binary talking to all of them</b> —
 * because the fan-out commits per schema and cannot be one transaction. The two skew directions are not
 * symmetric:
 *
 * <ul>
 *   <li><b>Schema ahead of code</b> (the tenant has a column this release never selects) is the state
 *       §4.6 already guarantees is harmless, and it is the state the deploy order deliberately produces:
 *       the migration Job runs to completion <em>before</em> the rollout, so a pod at head−1 routinely
 *       meets schemas at head. Served, silently, on purpose.</li>
 *   <li><b>Schema behind code</b> (this release selects a column that tenant's schema does not have) is
 *       a runtime failure with no clean shape — a 500 from deep inside a repository, per request, for as
 *       long as the fan-out lags. That is what this class refuses, at the edge, before a single tenant
 *       statement is issued.</li>
 * </ul>
 *
 * <p>So the comparison is deliberately ONE-SIDED. A tenant above the floor is served without comment;
 * only <em>below</em> is refused. A binary rolled back under a fleet that has already migrated forward
 * is therefore fully operational, which is the property that makes a rollback a real option.
 *
 * <h2>{@link #MIN_TENANT_SCHEMA_VERSION} is the promise, not the head</h2>
 *
 * <p>It is the highest tenant migration whose columns <em>this source tree reads</em> — not the highest
 * that exists in {@code db/migration/tenant}. §4.4's rule ("a tenant migration ships in release N; the
 * code that depends on it ships no earlier than N+1") is exactly the statement that those two numbers
 * are allowed to differ by a release, and raising this constant is how a release finally claims the
 * dependency. Raise it in the release that first selects the new column, never in the release that adds
 * it.
 *
 * <h2>Why the answer is remembered</h2>
 *
 * <p>The fact this asks about changes at deploy time and at no other time, so reading
 * {@code platform.tenant_placement} per request would be a database round trip on the hot path of every
 * tenant-scoped call to answer a question whose answer was fixed hours ago. It is therefore cached per
 * organization, in this process, with two lifetimes and a different argument for each:
 *
 * <ul>
 *   <li><b>Every verdict this class has READ expires on {@link #RECHECK_AFTER}</b> — the admissions
 *       exactly as much as the refusals — because since ADR 0011 an admission rests on <em>two</em>
 *       facts with different lifetimes, and a memo lives as long as the shortest fact under it.
 *       {@code schema_version} genuinely cannot go backwards (Flyway does not, absent a hand-run
 *       {@code repair}) and {@link #MIN_TENANT_SCHEMA_VERSION} is compiled in, so that half of an
 *       admission would be safe to remember for the life of the process. The other half is not:
 *       whether this deployment has a pool for the row's {@code datasource_name} is a statement about
 *       a column a cutover rewrites and a configuration a rollout changes. <b>The two must not share
 *       an entry shape</b>, and the shape they shared was the one that never expires — which is how a
 *       tenant re-pointed at a datasource this pod has no pool for went on being admitted here, and
 *       then failed at the BORROW instead: {@code UnknownTenantDataSourceException}, an
 *       INTERNAL_ERROR with no {@code Retry-After}, where §4.2 decided a bounded per-tenant 503.</li>
 *   <li><b>The expiry IS the invalidation, in both directions.</b> The migration runner is a Kubernetes
 *       Job in a different process and a cutover is another pod entirely — neither can push anything
 *       into a running pod, and a Valkey pub/sub channel for a fact that changes once in a tenant's
 *       lifetime would be machinery in place of a timer. So a verdict simply expires and the next
 *       request re-reads: a refused tenant recovers without a restart the moment the row or the
 *       configuration it named is fixed, and an admitted one stops being admitted the moment it is
 *       moved somewhere this deployment cannot follow. {@link #retryAfterSeconds()} advertises that
 *       same interval, which is the point: {@code Retry-After} is not a guess but the instant this
 *       process is next willing to change its mind, so a client that obeys the header arrives to a
 *       fresh read rather than to the same cached 503.</li>
 * </ul>
 *
 * <p>The hot path is still one map lookup; what the re-check costs is <em>one indexed read per tenant
 * per {@link #RECHECK_AFTER}</em> — the same cadence, against the same row, that {@link TenantRoutes}
 * already pays for the routing half of the same registry. The floor keeps its own read rather than
 * borrowing the router's memo because the two answer opposite failure policies (below), and a shared
 * read would have to pick one.
 *
 * <p><b>"Could not say" gets a third, slower lifetime</b> ({@code UNRESOLVED_RECHECK_AFTER}), because it
 * is not a refusal and V57's backfill makes it the state of the entire fleet for one deploy window. See
 * that constant.
 *
 * <p>A rollout replaces the process and therefore the whole cache, which is the only other way the floor
 * itself can move.
 *
 * <h2>Unknown is served, deliberately</h2>
 *
 * <p>No placement row, a null {@code schema_version}, an unparsable one, or a registry read that
 * failed — all are served, and logged. The alternative was tried on paper and is worse in kind: refusing
 * on a fact we could not establish converts one missing row (or one blip on the platform tier) into a
 * fleet-wide outage, which is the precise opposite of what the per-tenant check exists to deliver. The
 * loud failure is still available behind it — a genuinely missing column fails at ADR §3.3 layers 2–3,
 * loudly and locally — whereas a blanket 503 for every tenant is neither loud nor local. This mirrors
 * {@code CurrentUserFilter}'s own reasoning about a resolution that throws: "we could not ask" is not
 * "you may not".
 *
 * <h2>The second refusal: a datasource nobody configured (ADR 0011 §4.2)</h2>
 *
 * <p>Since ADR 0011 the placement row carries a second fact this binary must be able to honour:
 * {@code datasource_name}. A row naming a pool this deployment does not have — a typo'd name, a config
 * rollback racing a cutover — is refused with the same 503 + {@code Retry-After}, remembered for the
 * same {@link #RECHECK_AFTER}, and for the same reason the version refusal exists: the failure must be
 * THAT tenant's and bounded, not the pod's. It is a REFUSAL and not an unknown-so-serve, because unlike
 * a missing version this is a fact we READ — the registry answered, and the answer is one this
 * deployment cannot route. Serving would mean borrowing from a guessed pool, which is ADR 0010 §1's
 * misroute; the borrow path backs this up by throwing {@code UnknownTenantDataSourceException} for
 * whatever slips past the edge. Logged at ERROR once per transition into refusal (then re-checked
 * silently every {@link #RECHECK_AFTER}), naming the organization, the row's name and the config key
 * that would fix it.
 *
 * <p><b>This refusal is the one that had to become re-checkable, in both directions.</b> A missing
 * datasource is not a fact about the tenant, it is a fact about this deployment's configuration and
 * this instant's placement row — a name typo'd into the registry is corrected in a minute, a config
 * rollback is rolled forward, a cutover re-points a row that was fine when this process last looked.
 * So neither answer may be permanent: the refusal lifts on its own the moment the row or the config
 * agrees again, and the admission is re-earned on the same cadence rather than assumed for the life of
 * the pod. That is the whole difference between a bounded 503 the client is told to retry and a pod
 * that has to be restarted to serve a tenant it is perfectly able to serve.
 *
 * <h2>What it does not cover</h2>
 *
 * <p>The check is at the edge, on the tenant a REQUEST names. Work that enters a tenant from elsewhere —
 * a job's {@code runAs} loop, an operator route calling {@code TenantContext.runAs(orgId, …)} — is not
 * gated here and should not be: those paths are bounded, off the hot path, and their fan-out has the
 * registry open in front of it already.
 *
 * <p><strong>It is also not the router.</strong> This class reads one column of the placement row —
 * {@code schema_version} — to decide whether to SERVE a tenant. {@link TenantRoutes} reads another —
 * {@code schema_name} — to decide WHERE its rows are. The two memoize the same row for different
 * lifetimes and with opposite failure policies, and both differences are deliberate: an unreadable
 * registry is served here (refusing on a fact we could not establish is a fleet-wide outage) and
 * refused there (guessing a schema is how a write lands in the wrong one). A promotion drops both, in
 * the same call.
 */
@Component
public class TenantSchemaFloor {

    private static final Logger log = LoggerFactory.getLogger(TenantSchemaFloor.class);

    /**
     * The oldest tenant schema this binary will serve. See the class note: this is the highest tenant
     * migration this source tree DEPENDS on, which trails the highest that exists by design.
     *
     * <p>V53 ({@code tenant/V53__tenant_boundary_soft_refs.sql}) is the current head of the tenant
     * sequence and every tenant-tier entity in this tree maps against it, so the two numbers coincide
     * today. They stop coinciding the moment a tenant migration ships ahead of the code that reads it,
     * which is the ordering §4.4 mandates — at which point this constant stays put for a release.
     */
    public static final int MIN_TENANT_SCHEMA_VERSION = 53;

    /**
     * How long any verdict this class has read is believed — refusal and admission alike — and, the
     * same number on purpose, the {@code Retry-After} a refused tenant is given. See the class note:
     * making these one value is what stops a client from being told to come back sooner than this
     * process is willing to look again.
     *
     * <p>The default, and the number every deployment should keep. It is overridable
     * ({@code app.tenancy.schema-floor-recheck}) for the reason {@code app.tenancy.route-ttl} is:
     * a test that has to WATCH this process change its mind — refuse a tenant on an unconfigured
     * datasource, then serve it once the registry is fixed, without a restart — would otherwise have
     * to sleep half a minute to observe the one behaviour that matters.
     */
    public static final Duration RECHECK_AFTER = Duration.ofSeconds(30);

    /**
     * How long "the registry could not say" is remembered — deliberately far longer than a refusal,
     * because nobody is being refused and the only cost of being slow is being slow.
     *
     * <p>The number is sized by the state V57 leaves behind, not by taste: its backfill writes a row for
     * every organization that already existed and leaves {@code schema_version} NULL until the runner's
     * first pass fills them, so for one deploy window <em>the entire fleet</em> is unresolved. At the
     * refusal's 30 s that would be every active tenant re-reading the registry twice a minute, forever,
     * to re-learn a null. Five minutes makes it a rounding error, and there is nothing to be prompt
     * about: an unresolved tenant is already being served.
     */
    private static final Duration UNRESOLVED_RECHECK_AFTER = Duration.ofMinutes(5);

    /**
     * A ceiling on the memory this can hold, in tenants. One entry is a UUID key and a 24-byte record,
     * so even at this size it is single-digit megabytes; the bound exists because the key space is
     * "every organization that sends a request to this pod" and an unbounded map keyed on customer
     * count is a leak with a slow fuse. Crossing it drops everything rather than evicting cleverly:
     * the entries are rebuilt by one query each, the drop happens at most once per burst of new
     * tenants, and an LRU here would be a cache library's worth of code guarding a few megabytes.
     */
    private static final int MAX_TRACKED_TENANTS = 50_000;

    /**
     * The registry, read through its own class rather than through a second copy of its SQL — the
     * version is one column of a row {@code TenantPlacements} already knows how to select, and a query
     * kept here would be the one that goes stale the day the table changes. Read from the PLATFORM axis
     * while the request's own tenant is not yet pinned: from Phase 7 that ordering stops being a
     * formality, because a promoted tenant's axis reaches a different DATABASE, where this table does
     * not exist at all.
     */
    private final TenantPlacements placements;

    /** Who can answer "does a pool of this name exist here" — the ADR 0011 §4.2 half of the check. */
    private final TenantDataSources dataSources;

    /** {@link #RECHECK_AFTER}, or whatever the deployment set. Never zero: see the constructor. */
    private final Duration recheckAfter;

    private final ConcurrentHashMap<UUID, Entry> decisions = new ConcurrentHashMap<>();

    public TenantSchemaFloor(TenantPlacements placements, TenantDataSources dataSources,
            @Value("${app.tenancy.schema-floor-recheck:30s}") Duration recheckAfter) {
        this.placements = placements;
        this.dataSources = dataSources;
        // A zero or negative interval would make every verdict expire before it was stored — one
        // registry read per request forever — and a sub-second one would advertise Retry-After: 0,
        // which asks a client to come back instantly and is not a thing this process can honour.
        this.recheckAfter = recheckAfter == null || recheckAfter.compareTo(Duration.ofSeconds(1)) < 0
                ? RECHECK_AFTER : recheckAfter;
    }

    /**
     * May this process serve requests for {@code orgId}? {@code false} only when the registry says, in
     * so many words, one of the two things this binary cannot honour: the tenant's schema is BELOW
     * {@link #MIN_TENANT_SCHEMA_VERSION}, or its placement names a datasource this deployment has no
     * pool for (ADR 0011 §4.2).
     *
     * <p>Answered from memory on all but the first call per tenant (and per {@link #RECHECK_AFTER} while
     * a tenant is refused), so the hot path costs one map lookup. Must be called with no transaction
     * open and off the tenant's own axis — see {@link #placements}.
     */
    public boolean admits(UUID orgId) {
        long now = System.nanoTime();
        Entry cached = decisions.get(orgId);
        if (cached != null && cached.validAt(now)) {
            return cached.serve();
        }
        Entry fresh = read(orgId, cached);
        if (decisions.size() >= MAX_TRACKED_TENANTS) {
            decisions.clear();
        }
        decisions.put(orgId, fresh);
        return fresh.serve();
    }

    /** The {@code Retry-After} to give a refused tenant: the interval after which this will look again. */
    public long retryAfterSeconds() {
        return recheckAfter.toSeconds();
    }

    /**
     * Drop what this process remembers about {@code orgId}, so the next {@link #admits} re-reads the
     * registry. The one thing that can move a tenant's recorded version <em>without</em> a rollout, and
     * therefore the one caller: <b>promotion</b> ({@code TenantPromotionCaches.evictAfterPlacementFlip},
     * ADR 0010 §6 hop 0→1) rewrites the placement row this class read its answer out of.
     *
     * <p>Every entry expires on its own now (see {@link Entry}), so this shortens a bounded wait
     * rather than being the only cure — and the flip is exactly the moment where that distinction is
     * worth a call. A promotion rewrites both halves of the row this class read its answer out of, and
     * the flipping process must not spend the next {@link #RECHECK_AFTER} answering out of what the
     * row said before it moved: its own freeze arithmetic is sized on every OTHER process healing
     * within one route TTL, and it would be odd for the one process that knows to be the last to.
     *
     * <p>Silently tolerates a tenant this process never decided about, and a null. Both mean "there is
     * nothing remembered here", which is the state the caller wanted.
     */
    public void forget(UUID orgId) {
        if (orgId != null) {
            decisions.remove(orgId);
        }
    }

    private Entry read(UUID orgId, Entry previous) {
        Optional<TenantPlacement> placement;
        try {
            // An empty Optional is an ordinary state, not an error: §4.3 keeps provisioning free of DDL,
            // so a tenant can be perfectly serviceable while the registry has never named it.
            placement = placements.find(orgId);
        } catch (RuntimeException ex) {
            // RuntimeException and not just DataAccessException, and the extra breadth is load-bearing:
            // PlacementState.of THROWS on a state its enum does not name, which is precisely the
            // schema-ahead-of-code skew §4.4 exists to declare harmless. A registry that grew a fourth
            // state in the release before ours must not be able to take the fleet down from inside the
            // check whose whole job is to stop that happening.
            log.warn("Could not read the placement of tenant {}: {}. Serving it — the floor refuses on a"
                    + " version it has read, never on one it failed to read (ADR 0010 §4.4).",
                    orgId, ex.toString(), ex);
            return Entry.unresolved();
        }
        String namedDatasource = placement.map(TenantPlacement::dataSourceName).orElse(null);
        if (!dataSources.isConfigured(namedDatasource)) {
            // A fact we READ, not one we failed to establish — so it refuses where the nulls above
            // serve. Once per transition into refusal: the entry expires on RECHECK_AFTER and this
            // method re-runs, and a line per 30 s per broken tenant would bury the one that matters.
            if (previous == null || previous.serve()) {
                log.error("Tenant {} is placed on datasource '{}' and this deployment has no such pool —"
                        + " app.tenancy.datasources.{}.url is the config key that would create it"
                        + " (ADR 0011 §4.2). Its requests answer 503 with Retry-After {}s; every other"
                        + " tenant is unaffected, which is the whole point of refusing here rather than"
                        + " at boot.",
                        orgId, namedDatasource, namedDatasource, retryAfterSeconds());
            }
            return Entry.refused(recheckAfter);
        }
        String recorded = placement.map(TenantPlacement::schemaVersion).orElse(null);
        int version = majorVersion(recorded);
        if (version < 0) {
            // DEBUG, not WARN, and the level is a decision. Right after V57 this is the state of every
            // tenant in the fleet, so a warning per tenant would bury the one line above it that matters
            // — and "how many tenants have no recorded version" is a one-line query against the registry
            // that was built to answer exactly that, which is a better place to learn it than a log.
            log.debug("Tenant {} has no usable schema_version in platform.tenant_placement (found {});"
                    + " serving it. A tenant genuinely behind this binary will fail loudly at ADR 0010"
                    + " §3.3 layers 2-3 instead of cleanly here.", orgId, recorded);
            return Entry.unresolved();
        }
        if (version >= MIN_TENANT_SCHEMA_VERSION) {
            if (previous != null && !previous.serve()) {
                // Covers both refusals coming back: a migration that reached the tenant, and a
                // datasource this deployment can now route to. The row says which.
                log.info("Tenant {} is being served again: schema version {} (floor {}) on datasource"
                        + " '{}'.", orgId, recorded, MIN_TENANT_SCHEMA_VERSION, namedDatasource);
            }
            return Entry.served(recheckAfter);
        }
        log.warn("Tenant {} is at schema version {} and this binary requires {} (ADR 0010 §4.4). Its"
                + " requests answer 503 with Retry-After {}s until the tenant migration reaches it; every"
                + " other tenant is unaffected.",
                orgId, recorded, MIN_TENANT_SCHEMA_VERSION, retryAfterSeconds());
        return Entry.refused(recheckAfter);
    }

    /**
     * The leading integer of a Flyway version — {@code "53"} and {@code "53.1"} both yield 53, and 53.1
     * is correctly at the floor of 53 rather than below it. {@code -1} means "no version here", which
     * the caller reads as unknown and serves.
     */
    private static int majorVersion(String recorded) {
        if (recorded == null) {
            return -1;
        }
        String text = recorded.strip();
        int end = 0;
        while (end < text.length() && Character.isDigit(text.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return -1;
        }
        try {
            return Integer.parseInt(text.substring(0, end));
        } catch (NumberFormatException ex) {
            return -1; // a version longer than an int is not a version this repo's counter produces
        }
    }

    /**
     * One remembered decision and when this process stops believing it. Two lifetimes, and the class
     * note argues both: a verdict READ from the registry — served or refused — lasts
     * {@link #RECHECK_AFTER}, and a read that could not say lasts the slower
     * {@code UNRESOLVED_RECHECK_AFTER}.
     *
     * <p><b>Nothing here is permanent, and that is the ADR 0011 repair.</b> There used to be a third
     * shape, {@code SETTLED}, that never expired: correct for the only fact the class had in Phase 6
     * ({@code schema_version} cannot go backwards), and wrong the moment an admission also began to
     * rest on {@code datasource_name}, which any cutover may rewrite and any rollout may reconfigure.
     * A memo may not outlive the shortest-lived fact under it, so the surviving shapes both expire —
     * and the version half loses nothing by being re-read, because re-reading it can only confirm it.
     *
     * <p>{@code nanoTime} rather than the injected {@code Clock}: this is an interval, not an instant a
     * human will ever read or a row will ever hold, and a wall clock that steps backwards during an NTP
     * correction would extend a refusal indefinitely. The {@code now - expiresAt < 0} comparison is the
     * overflow-safe form the {@code nanoTime} javadoc prescribes — the same idiom, for the same reason,
     * as {@code DistributedRateLimiter}'s {@code retryNotBefore}.
     */
    private record Entry(boolean serve, long expiresAtNanos) {

        /** At or above the floor, on a pool this deployment has. Re-earned every {@code recheckAfter}. */
        static Entry served(Duration recheckAfter) {
            return new Entry(true, System.nanoTime() + recheckAfter.toNanos());
        }

        /** Below the floor, or placed nowhere this deployment can reach. Expires on the same interval,
         * which is the one this tenant's {@code Retry-After} promised. */
        static Entry refused(Duration recheckAfter) {
            return new Entry(false, System.nanoTime() + recheckAfter.toNanos());
        }

        /** The registry could not say. Served, and re-asked on the slow interval — nobody is waiting. */
        static Entry unresolved() {
            return new Entry(true, System.nanoTime() + UNRESOLVED_RECHECK_AFTER.toNanos());
        }

        boolean validAt(long now) {
            return now - expiresAtNanos < 0;
        }
    }
}
