package ug.co.smsone.shared.tenancy;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * <strong>Which schema one organization's rows are in, answered fast enough to ask on every connection
 * borrow.</strong> This is the placement router ADR 0010 §3.1 and §7 Phase 5 describe, and it is the
 * single fact that turns {@code platform.tenant_placement} from a registry somebody queries into the
 * thing that actually decides where a statement lands.
 *
 * <h2>Why it is a class and not two lines inside {@link TenantSchemas}</h2>
 *
 * <p>{@link TenantSchemas} is naming policy: it derives {@code t_<32hex>} from an organization id with
 * no reads at all, which is what ADR §3.1 means by "zero database reads to route". What it cannot
 * derive is <em>whether this tenant has been promoted yet</em> — that is a row, it changes at promotion
 * time, and it has to be read. So the naming stays pure and the reading lives here, memoized, because
 * the caller is {@code TenantRoutingDataSource.getConnection()} and a round trip per borrow to answer a
 * question whose answer changes once in a tenant's lifetime would be a permanent tax for a rare event.
 *
 * <h2>Three properties that are not optional</h2>
 *
 * <ol>
 *   <li><strong>It reads through the RAW pool, never through the routing DataSource.</strong> The
 *       router is what calls this; resolving a route by borrowing from the router would recurse until
 *       the stack ended. Taking {@link HikariDataSource} rather than {@code DataSource} is the guard —
 *       the routing wrapper is a {@code DelegatingDataSource}, so the type itself proves which one this
 *       got.</li>
 *   <li><strong>The query names {@code platform.} explicitly</strong>, so the {@code search_path} the
 *       previous borrower left on that pooled connection is irrelevant. AGENTS §1 states the same rule
 *       as a build gate, and here it is also what makes it safe to resolve a route without first having
 *       decided a route.</li>
 *   <li><strong>The lookup happens BEFORE the outer borrow, never while holding it.</strong>
 *       {@code TenantRoutingDataSource} resolves the path first and takes its connection second, so a
 *       cache miss costs one transient extra connection sequentially rather than a second connection
 *       held on top of the first. With a small pool the other order is a deadlock.</li>
 * </ol>
 *
 * <h2>How stale an answer can be, and why the promoter cares</h2>
 *
 * <p>Every answer — pooled and siloed alike — is remembered for {@link #routeTtl()}. That number is not
 * a cache-tuning knob; it is <strong>the width of the window in which one process can still be routing
 * a tenant to the schema it has just left</strong>, and it is therefore part of the promotion
 * procedure:
 *
 * <ul>
 *   <li>{@link #forget} drops the memo in THIS process, and {@code TenantPromotionCaches
 *       .evictAfterPlacementFlip} calls it — which fixes the promoting pod and nothing else.</li>
 *   <li>Every OTHER pod re-reads within {@link #routeTtl()}, and nothing pushes the flip to them. So
 *       {@code TenantPromoter} holds its freeze for a full {@link #routeTtl()} <em>after</em> the flip
 *       commits, before it deletes the source rows and before it lifts the freeze. Until that wait is
 *       over a stale pod reads rows that are still there and writes nothing (the RESTRICT window and
 *       {@code platform.tenant_freeze} between them stop every writer); after it, no pod can still be
 *       holding the old answer.</li>
 * </ul>
 *
 * <p>A Valkey broadcast on the cache-invalidation channel would shorten that wait, and would also add a
 * second, best-effort mechanism whose failure mode is a silent misroute. A timer's failure mode is a
 * slower promotion. The timer is chosen deliberately, and thirty seconds is the same interval
 * {@link TenantSchemaFloor#RECHECK_AFTER} already uses for the other fact this table decides.
 *
 * <h2>An unreadable registry is a failure, not a default</h2>
 *
 * <p>A read that throws is rethrown and the borrow fails. The alternative — fall back to
 * {@code tenant_pool} — is the exact failure ADR 0010 §1 calls the worst this design can produce: a
 * promoted tenant's write landing in a schema its {@code org_id} disagrees with, invisible until
 * somebody extracts that tenant and finds half its history missing. And the fallback buys nothing,
 * because until Phase 7 {@code platform.tenant_placement} lives in the same database, in the same pool,
 * as the statement that was about to run: a process that cannot read it was not going to serve the
 * request anyway. {@link TenantSchemaFloor} reasons the opposite way about a DIFFERENT question, and
 * the difference is the point — it decides whether to SERVE a tenant, where this decides WHERE its rows
 * are, and only one of those has a safe guess.
 */
@Component
public final class TenantRoutes {

    private static final Logger log = LoggerFactory.getLogger(TenantRoutes.class);

    /**
     * How long one organization's home is remembered in this process — and therefore how long a
     * promotion has to hold its freeze after the flip. See the class note; this is a correctness
     * parameter with a procedure attached, not a cache size.
     *
     * <p>Configurable as {@code app.tenancy.route-ttl} because it is the term in an arithmetic the
     * operator owns: <em>lower</em> costs one indexed read per tenant per interval per pod and shortens
     * every promotion's freeze, <em>higher</em> costs the opposite. The suite sets it to a second so a
     * promotion test is not twelve half-minute sleeps; production leaves it alone. Zero is legal and
     * means "re-read on every borrow" — correct, and a round trip per borrow.
     */
    public static final Duration DEFAULT_ROUTE_TTL = Duration.ofSeconds(30);

    /**
     * A ceiling on the memory this can hold, in tenants — the same bound and the same argument as
     * {@link TenantSchemaFloor}'s: the key space is "every organization this pod has served" and an
     * unbounded map keyed on customer count is a leak with a slow fuse. Crossing it drops everything
     * rather than evicting cleverly; each entry costs one indexed lookup to rebuild.
     */
    private static final int MAX_TRACKED_TENANTS = 50_000;

    /**
     * Qualified, and by {@code org_id} which is the primary key. {@code state} is read but deliberately
     * NOT used to choose a schema — see {@link #readHome}.
     */
    private static final String LOOKUP =
            "select schema_name, state from platform.tenant_placement where org_id = ?";

    /** Every schema the fleet occupies, for {@link SplitTables#homes()}. Ordered so a sweep is stable. */
    private static final String ALL_HOMES =
            "select distinct schema_name from platform.tenant_placement order by 1";

    /**
     * The instance {@link #homeOf(UUID)} consults. Static for the same reason {@link TenantContext} is:
     * the callers are {@link TenantSchemas#searchPathFor} on the connection-borrow path and
     * {@link SplitTables#homeOf} inside a caller's transaction, and neither can be handed a bean —
     * {@code TenantSchemas} is called from a {@code DataSource} that Hibernate builds its
     * {@code EntityManagerFactory} around, and {@code SplitTables} is a naming utility that fifty call
     * sites reach through a string concatenation.
     *
     * <p><strong>Last one installed wins, and that is safe here</strong> because every instance answers
     * out of the same {@code platform.tenant_placement} in the same database — a test JVM holding
     * several Spring contexts at once gets the same answer whichever pool the read goes through.
     */
    private static volatile TenantRoutes installed;

    /** So the "no router installed" warning is one line and not one line per borrow. */
    private static final AtomicBoolean WARNED_UNINSTALLED = new AtomicBoolean();

    /**
     * Shared across instances on purpose. A test JVM builds several Spring contexts over one database,
     * and a per-instance map would make a route resolved under one context invisible to the next while
     * both describe the same rows.
     */
    private static final ConcurrentHashMap<UUID, Route> ROUTES = new ConcurrentHashMap<>();

    private final DataSource pool;
    private final Duration ttl;

    TenantRoutes(HikariDataSource poolDataSource,
            @Value("${app.tenancy.route-ttl:30s}") Duration routeTtl) {
        this.pool = poolDataSource;
        this.ttl = routeTtl == null || routeTtl.isNegative() ? DEFAULT_ROUTE_TTL : routeTtl;
        install(this);
    }

    /**
     * The window a promotion has to hold its freeze for after the flip — {@code TenantPromoter} reads
     * this, and it must be the SAME number the memo below expires on or the wait proves nothing.
     *
     * <p>Falls back to the compiled default when nothing is installed, which is the only honest answer
     * available: a caller asking how long processes remember a route, in a process that has no router,
     * is asking about the other processes.
     */
    public static Duration routeTtl() {
        TenantRoutes routes = installed;
        return routes == null ? DEFAULT_ROUTE_TTL : routes.ttl;
    }

    private static void install(TenantRoutes routes) {
        installed = routes;
        // Not per-instance state: a second context's routes describe the same rows, but an entry
        // resolved through a pool that is about to be closed is not worth keeping either.
        ROUTES.clear();
    }

    /**
     * <strong>The schema {@code orgId}'s tenant-tier rows are in.</strong> {@code tenant_pool} for every
     * tenant nobody has promoted — which is the shipped case and the answer for a tenant the registry
     * has never heard of (ADR 0010 §4.3: a signup writes no DDL and a tenant with no row is pooled and
     * serving) — and {@code t_<32hex>} for one that has been.
     *
     * <p>Answered from memory on all but the first call per tenant per {@link #routeTtl()}.
     *
     * @throws IllegalStateException if the registry cannot be read, or names a schema that is not this
     *     tenant's. Both are refusals to guess: see the class note.
     */
    public static String homeOf(UUID orgId) {
        if (orgId == null) {
            throw new IllegalArgumentException(
                    "a tenant home is looked up by organization id; null names no tenant."
                            + " SplitTables.homeOf answers PLATFORM for a null org before it gets here.");
        }
        TenantRoutes routes = installed;
        if (routes == null) {
            // Reachable only before the bean exists — startup work runs on the platform axis or on none,
            // and both resolve without ever coming here. Loud once, because if it is ever NOT startup it
            // is a tenant being routed by a default instead of by its registry row.
            if (WARNED_UNINSTALLED.compareAndSet(false, true)) {
                log.warn("A tenant home was asked for before TenantRoutes was built, so organization {}"
                        + " resolved to {} from naming policy alone. That is correct for every pooled"
                        + " tenant and WRONG for a promoted one. If this line appears outside application"
                        + " startup, something is routing before the context is ready.",
                        orgId, TenantSchemas.TENANT_POOL);
            }
            return TenantSchemas.TENANT_POOL;
        }
        return routes.resolve(orgId);
    }

    /**
     * Every schema the fleet's tenant rows can be in — {@code tenant_pool} plus every silo the registry
     * names, whatever state it is in.
     *
     * <p><strong>This is not a sweep list.</strong> It answers "where could a row for some tenant be",
     * which is what a cross-home queue claim and a test's cleanup need. A background job that must not
     * touch a tenant mid-promotion asks {@link TenantFanOut} instead, which subtracts the frozen and
     * the half-built ones and is the only thing that may decide where work happens.
     */
    public static List<String> tenantHomes() {
        TenantRoutes routes = installed;
        if (routes == null) {
            return List.of(TenantSchemas.TENANT_POOL);
        }
        return routes.readHomes();
    }

    /**
     * Drop what this process remembers about {@code orgId}, so the next route resolution re-reads the
     * registry. The one caller that matters is the placement flip
     * ({@code TenantPromotionCaches.evictAfterPlacementFlip}); the test fixture that places a silo
     * ({@code TenantSilos.place}) is the other.
     *
     * <p>It fixes THIS process only. Every other one heals on {@link #routeTtl()}, which is why the
     * promoter waits that long before it deletes the source rows or lifts the freeze.
     */
    public static void forget(UUID orgId) {
        if (orgId != null) {
            ROUTES.remove(orgId);
        }
    }

    /**
     * Drop every remembered route. For the test fixture that drops silo schemas out from under the
     * registry: a memo naming a schema that no longer exists would put the next borrow on a
     * {@code search_path} pointing at nothing, and the failure would name neither the fixture nor the
     * test that leaked.
     */
    public static void forgetEverything() {
        ROUTES.clear();
    }

    private String resolve(UUID orgId) {
        long now = System.nanoTime();
        Route cached = ROUTES.get(orgId);
        if (cached != null && cached.validAt(now)) {
            return cached.schema();
        }
        String schema = readHome(orgId);
        if (ROUTES.size() >= MAX_TRACKED_TENANTS) {
            ROUTES.clear();
        }
        ROUTES.put(orgId, Route.expiringIn(schema, ttl));
        if (cached != null && !cached.schema().equals(schema)) {
            // The only way this happens without a local forget() is another process having promoted or
            // demoted this tenant. Worth a line: it is the observable half of the ROUTE_TTL window.
            log.info("tenant {} now routes to {} (was {}) — the registry moved it while this process was"
                    + " holding the previous answer", orgId, schema, cached.schema());
        }
        return schema;
    }

    /**
     * One registry read.
     *
     * <p><strong>{@code state} is read for the log line and never for the decision.</strong> A
     * PROVISIONING row still names the schema the tenant's rows are IN — {@code beginRelocation} moves
     * the state and leaves {@code schema_name} alone, and {@code completeRelocation} moves both in one
     * statement precisely so no reader can ever see a new home beside a stale state. So routing by
     * {@code schema_name} alone is correct in every state, and refusing to route a PROVISIONING tenant
     * would turn a promotion into an outage for the tenant being promoted.
     */
    private String readHome(UUID orgId) {
        try (Connection connection = pool.getConnection();
                PreparedStatement statement = connection.prepareStatement(LOOKUP)) {
            statement.setObject(1, orgId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return TenantSchemas.TENANT_POOL;
                }
                return requireThisTenantsSchema(orgId, rows.getString("schema_name"));
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("could not read the home of organization " + orgId
                    + " from platform.tenant_placement, so there is no schema this statement may be"
                    + " addressed to. Guessing tenant_pool here is how a promoted tenant's write lands"
                    + " in a schema its org_id disagrees with (ADR 0010 §1), so this fails instead.",
                    failure);
        }
    }

    /**
     * The registry names a schema; this proves it is one this tenant could possibly be in.
     *
     * <p>The silo name is derived from the organization id with zero reads, so a row whose two columns
     * disagree is not a naming quirk — it is a row that would put this tenant's statements in ANOTHER
     * tenant's schema. {@code TenantHome.silo} makes the identical check for the fan-out; this is the
     * request path's copy of it, and both exist because the string is interpolated into a
     * {@code SET search_path} that cannot bind it.
     */
    private static String requireThisTenantsSchema(UUID orgId, String recorded) {
        if (TenantSchemas.TENANT_POOL.equals(recorded)) {
            return TenantSchemas.TENANT_POOL;
        }
        String expected = TenantSchemas.siloSchema(orgId);
        if (expected.equals(TenantSchemas.requireSiloSchema(recorded))) {
            return expected;
        }
        throw new IllegalStateException("platform.tenant_placement says organization " + orgId
                + " lives in schema '" + recorded + "', but a silo for that tenant can only ever be '"
                + expected + "' (ADR 0010 §3.1). Routing to the recorded name would run this tenant's"
                + " statements inside another tenant's schema.");
    }

    private List<String> readHomes() {
        List<String> homes = new ArrayList<>();
        homes.add(TenantSchemas.TENANT_POOL);
        try (Connection connection = pool.getConnection();
                PreparedStatement statement = connection.prepareStatement(ALL_HOMES);
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String schema = rows.getString(1);
                if (!homes.contains(schema)) {
                    // Validated on the way out: these names are interpolated into SQL by every caller.
                    homes.add(TenantSchemas.requireSiloSchema(schema));
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("could not enumerate the schemas the fleet occupies from"
                    + " platform.tenant_placement; a cross-home sweep that carried on with a short list"
                    + " would silently skip whichever homes it could not see", failure);
        }
        return List.copyOf(homes);
    }

    /**
     * One remembered home and when this process stops believing it.
     *
     * <p>{@code nanoTime} rather than a wall clock, and the {@code now - expiresAt < 0} comparison, for
     * the reason {@link TenantSchemaFloor}'s own {@code Entry} states: this is an interval nobody reads
     * and no row holds, and a clock that steps backwards under an NTP correction must not be able to
     * extend it.
     */
    private record Route(String schema, long expiresAtNanos) {

        static Route expiringIn(String schema, Duration ttl) {
            return new Route(schema, System.nanoTime() + ttl.toNanos());
        }

        boolean validAt(long now) {
            return now - expiresAtNanos < 0;
        }
    }
}
