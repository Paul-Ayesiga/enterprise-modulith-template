package ug.co.smsone.shared.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The stale-while-unreachable layer of ADR 0011 §2: a per-cache <b>holdover</b> of the last
 * authoritative answer per key, served only when the authority cannot be asked, bounded by the hard
 * ceiling in {@link IdentityStaleProperties}.
 *
 * <p><b>Why this is a separate structure and not a longer cache TTL.</b> {@link TwoLevelCache} is the
 * freshness substrate — its TTLs say how long an answer may be REUSED without re-asking. The ceiling
 * bounds something different: how long since this fact was last KNOWN true (§2.3). Conflating them
 * (stretching the cache TTL to the ceiling) would grant every answer fifteen minutes of reuse in
 * ordinary operation; and re-caching a stale-served answer would restart its TTL, compounding the two
 * bounds. So the holdover sits beside the cache: {@code refresh(...)} is called from inside the
 * {@code @Cacheable} loader — the one place that runs only on a genuine authoritative read, never on a
 * cache hit — and {@code serveOr(...)} is called from OUTSIDE the cache, so a stale answer is never
 * written back as fresh.
 *
 * <p><b>"Unreachable" is a connection answer, never a data answer (§2.2), and the classification lives
 * here once</b> so three call sites cannot drift. An authoritative "absent" is an answer:
 * {@code refresh(key, null)} REPLACES the entry, so the stale path cannot resurrect a deleted person —
 * miss that and the erasure pipeline un-erases people for fifteen minutes. A query-shaped error
 * (missing relation, constraint, syntax) is a bug and {@link Holdover#serveOr} rethrows it: serving
 * stale over a bug converts a loud failure into a silently wrong answer with a fifteen-minute fuse.
 *
 * <p><b>Age is a property of the entry, not of the outage (§2.3).</b> Each entry carries the instant of
 * its last successful refresh from the injected {@link Clock}; an entry last confirmed fourteen minutes
 * before the outage began has one minute of grace, not fifteen. A cache HIT does not advance the
 * instant — hits run no loader — so the measurement can only be conservative.
 *
 * <p><b>Per process, deliberately.</b> A pod serves stale only what it itself confirmed; a pod with no
 * entry denies (503, {@link PlatformUnreachableException}), which is the posture the ports already have
 * for an unanswerable question. Sharing holdovers through L2 would put the fallback in the same Valkey
 * whose reachability nobody promised, and would let one pod's clock argue with another's.
 *
 * <p><b>What must never gain a holdover:</b> {@code org-permissions} ({@code OrgAuthorization} — AGENTS
 * §5.5: a revoked membership denies the very next request; unreachable authority = deny, no ceiling, no
 * grace) and {@code TenantRoutes} (a misroute is ADR 0010 §1's worst failure; a refusal is a bounded
 * 503). {@code PlatformUnreachableIntegrationTest} is written to go red if either one starts serving
 * last-known answers.
 */
@Component
public class StaleWhileUnreachable {

    private static final Logger log = LoggerFactory.getLogger(StaleWhileUnreachable.class);

    private final Clock clock;
    private final Duration ceiling;
    private final MeterRegistry meters;
    private final ConcurrentHashMap<String, Holdover<?>> holdovers = new ConcurrentHashMap<>();

    public StaleWhileUnreachable(Clock clock, IdentityStaleProperties properties, MeterRegistry meters) {
        this.clock = clock;
        this.ceiling = properties.ceiling();
        this.meters = meters;
    }

    /**
     * The holdover for one cache, memoized by name. The type parameter is the caller's promise — each
     * cache has exactly one owning class, so the unchecked cast cannot be wrong without that class
     * disagreeing with itself.
     */
    @SuppressWarnings("unchecked")
    public <T> Holdover<T> holdoverFor(String cacheName) {
        return (Holdover<T>) holdovers.computeIfAbsent(cacheName, Holdover::new);
    }

    /**
     * Every cache that has claimed a holdover on this instance — the complete list of what CAN be
     * served after the authority stops answering.
     *
     * <p>Exists so the "what must never gain a holdover" paragraph in the class note is CHECKABLE and
     * not merely written down. Each owner claims its holdover in its own constructor, so on a started
     * context this set is the whole truth, and {@code PlatformUnreachableIntegrationTest} reads it to
     * assert that {@code org-permissions} is absent. That assertion catches the wiring the behavioural
     * tests can only catch through an outage: one {@code stale.holdoverFor(CACHE)} added to
     * {@code PermissionResolver} would hand a revoked member their old grant for fifteen minutes, and
     * a reviewer looking at that one line would see an idiom the other three caches already use.
     *
     * <p>Also the honest answer to "what is currently allowed to go stale" during an incident.
     */
    public Set<String> heldCaches() {
        return Set.copyOf(holdovers.keySet());
    }

    /**
     * Could the authority not be ASKED — as opposed to answering something we dislike (§2.2)? Walks the
     * cause chain because every layer wraps: Hikari's borrow timeout arrives inside Spring's
     * {@code CannotCreateTransactionException} or {@code DataAccessResourceFailureException}, and the
     * socket-level truth is several causes down.
     *
     * <p>Public because the classification is needed by more than the holdovers: a read that must
     * NEVER serve stale (the org-status half of {@code PermissionResolver}) still has to tell "the
     * platform could not be asked" (refuse, 503) apart from "the query is a bug" (rethrow), and a
     * second copy of this list would drift from the first.
     */
    public static boolean connectionShaped(Throwable failure) {
        for (Throwable cause = failure; cause != null;
                cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof java.net.ConnectException
                    || cause instanceof java.net.UnknownHostException
                    || cause instanceof java.net.SocketTimeoutException
                    || cause instanceof java.net.SocketException
                    || cause instanceof SQLTransientConnectionException
                    || cause instanceof SQLNonTransientConnectionException
                    || cause instanceof SQLRecoverableException
                    || cause instanceof org.springframework.dao.DataAccessResourceFailureException) {
                return true;
            }
            // Deliberately NOT QueryTimeoutException alone: a server-side statement_timeout cancel is
            // the authority ANSWERING (an answer we dislike), while a statement dying at the SOCKET
            // timeout carries a SocketTimeoutException in its chain and is matched above.
            // SQLState class 08 is "connection exception" — the driver's own word for it, and the one
            // classification that survives a driver wrapping its failures in plain SQLException.
            //
            // The three 57P0x states are the server SEVERING or REFUSING the session — admin shutdown
            // (57P01, what a failover, a restart, or pg_terminate_backend delivers mid-session),
            // crash shutdown (57P02), cannot-connect-now (57P03). None is class 08 and none arrives
            // Hikari-wrapped when the FATAL lands on an already-borrowed connection, so without them a
            // real failover was rethrown as a "bug" instead of degrading to the holdover — measured in
            // the Phase 7 gate: GenericJDBCException over 57P01 escaped this classifier untouched.
            // NEVER 57014 (query_canceled): a statement_timeout cancel is the authority answering.
            if (cause instanceof SQLException sql && sql.getSQLState() != null
                    && (sql.getSQLState().startsWith("08")
                            || sql.getSQLState().equals("57P01")
                            || sql.getSQLState().equals("57P02")
                            || sql.getSQLState().equals("57P03"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * One cache's last-known answers. {@code refresh} from inside the {@code @Cacheable} loader,
     * {@code serveOr} from the catch around the cached call, {@code forget} beside every keyed cache
     * eviction whose reason is "the answer may now be different" (a re-pointed subject must not survive
     * here either).
     */
    public final class Holdover<T> {

        /**
         * Same bound-and-clear idiom as {@code TenantSchemaFloor.MAX_TRACKED_TENANTS}: the key space is
         * "every identity this pod resolved", so an unbounded map is a leak with a slow fuse, and an LRU
         * would be a cache library's worth of code guarding megabytes. A clear costs the next outage its
         * grace for the dropped keys — they deny at the ceiling, which is the safe direction.
         */
        private static final int MAX_ENTRIES = 200_000;

        private final String cacheName;
        private final ConcurrentHashMap<String, Entry<T>> entries = new ConcurrentHashMap<>();
        // WARN once on entering stale service, ERROR once on the first ceiling denial, INFO on
        // recovery (§2.3: silent stale service is how a 15-minute ceiling becomes a 15-minute mystery).
        private final AtomicBoolean servingStale = new AtomicBoolean();
        private final AtomicBoolean deniedAtCeiling = new AtomicBoolean();
        private final AtomicLong oldestServedAgeSeconds = new AtomicLong();
        private final Counter staleServed;
        private final Counter ceilingDenials;

        private Holdover(String cacheName) {
            this.cacheName = cacheName;
            this.staleServed = Counter.builder("smsone.cache.stale_served")
                    .description("Answers served from the last-known holdover while the platform was"
                            + " unreachable (ADR 0011 §2)")
                    .tag("cache", cacheName)
                    .register(meters);
            this.ceilingDenials = Counter.builder("smsone.cache.stale_ceiling_denials")
                    .description("Reads denied because the platform was unreachable and the last-known"
                            + " answer had aged past the ceiling (ADR 0011 §2.3)")
                    .tag("cache", cacheName)
                    .register(meters);
            Gauge.builder("smsone.cache.stale_oldest_served_age_seconds", oldestServedAgeSeconds,
                            AtomicLong::get)
                    .description("Oldest last-known answer served during the current stale-service"
                            + " window; 0 while the authority is answering")
                    .tag("cache", cacheName)
                    .register(meters);
        }

        /**
         * Records an authoritative answer — including {@code null}, which is authoritative ABSENT and
         * replaces whatever was held (§2.2: never resurrect a deleted person). Returns the value so the
         * loader can {@code return holdover.refresh(key, value)}.
         */
        public T refresh(String key, T value) {
            if (entries.size() >= MAX_ENTRIES) {
                entries.clear();
            }
            entries.put(key, new Entry<>(value, clock.instant()));
            if (servingStale.compareAndSet(true, false)) {
                deniedAtCeiling.set(false);
                oldestServedAgeSeconds.set(0);
                log.info("Cache '{}' resumed authoritative service — the platform answered again after a"
                        + " window of stale service (ADR 0011 §2).", cacheName);
            }
            return value;
        }

        /**
         * The outage path. Rethrows {@code failure} unless it is connection-shaped (§2.2 — stale over a
         * bug is a silently wrong answer); serves the last-known value while its age is under the
         * ceiling (the value may be null: a last-known ABSENT is served as absent); at the ceiling — or
         * with nothing held — throws the 503 the caller's edge renders with {@code Retry-After}.
         */
        public T serveOr(String key, RuntimeException failure) {
            if (!connectionShaped(failure)) {
                throw failure;
            }
            Entry<T> entry = entries.get(key);
            Instant now = clock.instant();
            if (entry != null) {
                Duration age = Duration.between(entry.refreshedAt(), now);
                if (age.compareTo(ceiling) < 0) {
                    staleServed.increment();
                    oldestServedAgeSeconds.accumulateAndGet(age.toSeconds(), Math::max);
                    if (servingStale.compareAndSet(false, true)) {
                        log.warn("Cache '{}' entered STALE service: the platform is unreachable ({}) and"
                                + " last-known answers are being served up to the {} ceiling, each aged"
                                + " from its own last refresh (ADR 0011 §2).",
                                cacheName, failure.toString(), ceiling);
                    }
                    return entry.value();
                }
            }
            ceilingDenials.increment();
            if (deniedAtCeiling.compareAndSet(false, true)) {
                log.error("Cache '{}' DENIED at the staleness ceiling: the platform is unreachable ({})"
                        + " and this key's last confirmed answer is {} — older than {} or never held."
                        + " Callers get 503 + Retry-After until the platform answers (ADR 0011 §2.3).",
                        cacheName, failure.toString(),
                        entry == null ? "absent" : "aged " + Duration.between(entry.refreshedAt(), now),
                        ceiling, failure);
            }
            throw new PlatformUnreachableException("A platform-side lookup is temporarily unavailable"
                    + " and its last known answer has aged out. Nothing is wrong with your credentials —"
                    + " retry after the interval in the Retry-After header.");
        }

        /**
         * Drops one key — the companion of every keyed cache eviction that exists because the answer may
         * now be DIFFERENT (a subject re-linked to another person). An eviction that cleared the cache
         * but left the holdover would serve the superseded answer through the next outage.
         */
        public void forget(String key) {
            entries.remove(key);
        }

        /**
         * Drops everything — the companion of an {@code allEntries} eviction taken for the same reason
         * {@link #forget} accompanies a keyed one. Strictly over-forgetting: dropped keys deny at the
         * next outage instead of serving, which is the safe direction.
         */
        public void clear() {
            entries.clear();
        }
    }

    private record Entry<T>(T value, Instant refreshedAt) {
    }
}
