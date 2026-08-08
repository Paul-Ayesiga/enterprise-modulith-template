package ug.co.smsone.shared.tenancy;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
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
 *   <li><b>"At or above the floor" is remembered permanently, and needs no invalidation</b> — the
 *       transition it records is one-way. {@code schema_version} only ever increases (Flyway does not go
 *       backwards without a hand-run {@code repair}) and {@link #MIN_TENANT_SCHEMA_VERSION} is compiled
 *       in, so a tenant that has met THIS process's floor cannot stop meeting it while THIS process
 *       lives. There is no event to subscribe to because there is no transition to miss.</li>
 *   <li><b>"Below the floor" is remembered for {@link #RECHECK_AFTER} only, and that expiry IS the
 *       invalidation.</b> The migration runner is a Kubernetes Job in a different process — it cannot
 *       push anything into a running pod, and a Valkey pub/sub channel for a fact that settles once per
 *       deploy would be machinery in place of a timer. So the refusal simply expires and the next
 *       request re-reads. {@link #retryAfterSeconds()} advertises that same interval, which is the point:
 *       {@code Retry-After} is not a guess but the instant this process is next willing to change its
 *       mind, so a client that obeys the header arrives to a fresh read rather than to the same cached
 *       503.</li>
 * </ul>
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
 * <h2>What it does not cover</h2>
 *
 * <p>The check is at the edge, on the tenant a REQUEST names. Work that enters a tenant from elsewhere —
 * a job's {@code runAs} loop, an operator route calling {@code TenantContext.runAs(orgId, …)} — is not
 * gated here and should not be: those paths are bounded, off the hot path, and their fan-out has the
 * registry open in front of it already. Phase 5's promoter is where the placement row is consulted for
 * every other purpose.
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
     * How long a refusal is remembered, and — the same number, on purpose — the {@code Retry-After} a
     * refused tenant is given. See the class note: making these one value is what stops a client from
     * being told to come back sooner than this process is willing to look again.
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

    private final ConcurrentHashMap<UUID, Entry> decisions = new ConcurrentHashMap<>();

    public TenantSchemaFloor(TenantPlacements placements) {
        this.placements = placements;
    }

    /**
     * May this process serve requests for {@code orgId}? {@code false} only when the registry says, in
     * so many words, that the tenant's schema is BELOW {@link #MIN_TENANT_SCHEMA_VERSION}.
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
        return RECHECK_AFTER.toSeconds();
    }

    private Entry read(UUID orgId, Entry previous) {
        String recorded;
        try {
            // An empty Optional is an ordinary state, not an error: §4.3 keeps provisioning free of DDL,
            // so a tenant can be perfectly serviceable while the registry has never named it.
            recorded = placements.find(orgId).map(TenantPlacement::schemaVersion).orElse(null);
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
                log.info("Tenant {} reached schema version {} (floor {}) and is being served again.",
                        orgId, recorded, MIN_TENANT_SCHEMA_VERSION);
            }
            return Entry.SETTLED;
        }
        log.warn("Tenant {} is at schema version {} and this binary requires {} (ADR 0010 §4.4). Its"
                + " requests answer 503 with Retry-After {}s until the tenant migration reaches it; every"
                + " other tenant is unaffected.",
                orgId, recorded, MIN_TENANT_SCHEMA_VERSION, retryAfterSeconds());
        return Entry.refused();
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
     * One remembered decision, in the three lifetimes the class note argues for: {@code settled} never
     * expires (the transition it records is one-way), a refusal expires on {@link #RECHECK_AFTER}, and an
     * unresolved read on the slower {@code UNRESOLVED_RECHECK_AFTER}.
     *
     * <p>{@code nanoTime} rather than the injected {@code Clock}: this is an interval, not an instant a
     * human will ever read or a row will ever hold, and a wall clock that steps backwards during an NTP
     * correction would extend a refusal indefinitely. The {@code now - expiresAt < 0} comparison is the
     * overflow-safe form the {@code nanoTime} javadoc prescribes — the same idiom, for the same reason,
     * as {@code DistributedRateLimiter}'s {@code retryNotBefore} — and it is why the settled case is a
     * flag rather than an expiry of {@code Long.MAX_VALUE}, which that subtraction would overflow.
     */
    private record Entry(boolean serve, boolean settled, long expiresAtNanos) {

        /** At or above the floor: one-way, so there is nothing to invalidate and no expiry to set. */
        private static final Entry SETTLED = new Entry(true, true, 0L);

        /** Below the floor. Expires on the interval this tenant's {@code Retry-After} promised. */
        static Entry refused() {
            return new Entry(false, false, System.nanoTime() + RECHECK_AFTER.toNanos());
        }

        /** The registry could not say. Served, and re-asked on the slow interval — nobody is waiting. */
        static Entry unresolved() {
            return new Entry(true, false, System.nanoTime() + UNRESOLVED_RECHECK_AFTER.toNanos());
        }

        boolean validAt(long now) {
            return settled || now - expiresAtNanos < 0;
        }
    }
}
