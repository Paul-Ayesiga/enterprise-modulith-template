package ug.co.smsone.gateway.route;

import java.net.URI;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import ug.co.smsone.gateway.core.route.ServiceDefinition;
import ug.co.smsone.gateway.core.route.ServiceRegistry;
import ug.co.smsone.gateway.core.security.EdgeOrganization;

/**
 * The claim → upstream decision table: which organizations are served by a deployment of their own, and
 * which are served by the shared modulith (ADR 0010 §7 Phase 8). {@link TenantUpstreamProperties}
 * records WHY this is configuration rather than a query against {@code platform.tenant_placement};
 * this class is what makes the configuration safe to act on.
 *
 * <h2>The snapshot, and what evicts it</h2>
 *
 * <p>Configuration alone does not answer the question: an entry names a {@code gateway.services} id, and
 * services are MUTABLE at runtime — {@code DiscoveryRegistrar} registers and deregisters them, and the
 * admin service registrar can too. So the live answer is (config × registry), and this class holds it
 * as an immutable snapshot swapped whole on {@link RefreshRoutesEvent} — the same signal the route table
 * and the edge's policy map already rebuild on. Register a tenant's service and its tenants route to it
 * on the next request, with no restart.
 *
 * <p><b>The snapshot is not a performance cache and it would be dishonest to call it one</b> — the
 * registry behind it is a concurrent map and a per-request lookup would cost nothing. It buys two things
 * that matter more. A decision cannot change in the middle of a request-serving epoch, so a discovery
 * blip cannot flip one tenant between "dedicated" and "refused" invisibly. And every transition is
 * logged exactly once, at the moment it happens, naming the tenant and the config key that fixes it —
 * which is the difference between a misconfigured cutover you find in a log and one you find in a
 * support ticket.
 *
 * <h2>Fail closed, per tenant, never per pod</h2>
 *
 * <p>An entry naming a service nobody registered is refused with a 503 for THAT organization and is
 * otherwise inert: every other tenant, including the pooled majority who have no entry at all, is served
 * normally. It is deliberately not a startup failure. ADR 0011 §4.2 decided this exact question one
 * layer down — a placement row naming an unconfigured datasource fails closed per tenant, never per pod,
 * because the alternative converts one typo into a fleet-wide outage — and the argument is stronger at
 * the edge, where a pod that refuses to boot takes down every tenant the gateway fronts.
 *
 * <p>What it must never do is fall THROUGH to the shared deployment. A tenant reaches this table only
 * once someone has moved it, and by then the shared modulith's database no longer holds its rows: a
 * silent fallback answers with an empty tenant instead of an error, which is the misroute ADR 0011 §2.4
 * names as the worst failure available. A bounded 503 is strictly the cheaper wrong answer.
 *
 * <h2>The one failure here that IS silent, and the counter that ends it</h2>
 *
 * <p>Every way this table can be wrong announces itself — an unregistered service logs and refuses, a
 * self-contradicting entry refuses — except one: <b>a key spelled in an id-space no credential
 * carries</b>. Three different id-spaces can name one organization, and only two of them ever reach
 * here. A JWT carries the Keycloak organization ALIAS and the PROVIDER id inside that claim; an API key
 * introspects to the platform's own {@code organization.id}. {@code platform.tenant_placement} is keyed
 * on that third one — and it is the only spelling written down anywhere, so it is the one an operator
 * naturally copies into {@code gateway.tenancy.upstreams}. Every JWT caller for that tenant then matches
 * NEITHER key, {@link #decide} returns {@link Decision#shared()}, and the tenant is served from the
 * shared database that no longer holds its rows. Indistinguishable, from in here, from a tenant that
 * simply has no entry.
 *
 * <p>Nothing at the edge can tell those two apart — so instead of guessing, every configured key counts
 * the callers it matches. A key with {@code matches: 0} on {@code /actuator/gatewaytenants} while its
 * tenant is live is a key no credential carries, and the first caller a key ever matches is logged once.
 * The runbook makes that observation a GATE: a non-zero count before {@code tenant_placement} is
 * flipped, which is the ordering that keeps the disagreement window pointing at the database that still
 * holds the rows.
 */
@Component
public class TenantUpstreams implements ApplicationListener<RefreshRoutesEvent> {

    /** Upstream faults and the config that produces them belong to the operator's stream, not the caller's. */
    private static final Logger errorLog = LoggerFactory.getLogger("gateway.error");
    private static final Logger log = LoggerFactory.getLogger(TenantUpstreams.class);

    private final TenantUpstreamProperties properties;
    private final ServiceRegistry services;
    /**
     * One counter per CONFIGURED key, built once and never added to — an unmatched organization must
     * never create an entry here, or a busy gateway would grow a map keyed by every tenant it fronts.
     * Fixed key set, so an unmodifiable map is safe to read concurrently without synchronisation.
     */
    private final Map<String, Matches> matches;
    private volatile Snapshot snapshot;

    TenantUpstreams(TenantUpstreamProperties properties, ServiceRegistry services) {
        this.properties = properties;
        this.services = services;
        Map<String, Matches> counters = new LinkedHashMap<>();
        properties.upstreams().keySet().forEach(key -> counters.put(key, new Matches()));
        this.matches = Collections.unmodifiableMap(counters);
        this.snapshot = resolve();
        report(null, snapshot);
    }

    @Override
    public void onApplicationEvent(RefreshRoutesEvent event) {
        Snapshot previous = snapshot;
        Snapshot rebuilt = resolve();
        snapshot = rebuilt;
        report(previous, rebuilt);
    }

    /** Which deployment serves this caller. Never null; {@link Decision#shared()} is the default. */
    Decision decide(EdgeOrganization organization) {
        if (organization == null || organization.isEmpty() || properties.upstreams().isEmpty()) {
            return Decision.shared();
        }
        Map<String, String> configured = properties.upstreams();
        String byAlias = organization.alias() == null ? null : configured.get(organization.alias());
        String byId = organization.id() == null ? null : configured.get(organization.id());
        if (byAlias == null && byId == null) {
            return Decision.shared(); // every tenant today: no entry, no change
        }
        // Credit every key that matched, BEFORE the outcome is known — the question the counter answers
        // is "does any credential actually carry this spelling", and a key that matches and then refuses
        // has answered it. Both spellings are credited when both matched, because that is what tells an
        // operator which of the two is carrying the traffic.
        if (byAlias != null) {
            matched(organization.alias());
        }
        if (byId != null) {
            matched(organization.id());
        }
        if (byAlias != null && byId != null && !byAlias.equals(byId)) {
            // The token's two spellings of one organization were configured to two different
            // deployments. One of them is wrong and nothing here can tell which, so serving either is
            // a coin flip between "correct" and "this tenant's rows are in the other database".
            return Decision.refused(organization, byAlias + "|" + byId, Decision.AMBIGUOUS_CONFIG,
                    "is configured to two different upstreams (by alias and by id) — "
                            + "gateway.tenancy.upstreams disagrees with itself");
        }
        String serviceId = byAlias != null ? byAlias : byId;
        ServiceDefinition service = snapshot.resolved().get(serviceId);
        if (service == null) {
            return Decision.refused(organization, serviceId, Decision.UNRESOLVED_SERVICE,
                    "names service '" + serviceId + "', which no gateway.services entry registers");
        }
        return Decision.dedicated(service);
    }

    /**
     * The live table, for the {@code gatewaytenants} admin endpoint. Ordered as configured.
     *
     * <p>{@code resolved} answers a question about the SERVICE and says nothing about the KEY, which is
     * the half a cutover gets wrong: an entry keyed in the wrong id-space resolves perfectly and matches
     * nobody. {@code matches}/{@code lastMatched} are the other half — see the class note.
     */
    public Map<String, Map<String, Object>> table() {
        Snapshot current = snapshot;
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        properties.upstreams().forEach((organization, serviceId) -> {
            ServiceDefinition service = current.resolved().get(serviceId);
            Matches counter = matches.get(organization);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("serviceId", serviceId);
            row.put("resolved", service != null);
            row.put("instances", service == null ? List.<String>of()
                    : service.instances().stream().map(URI::toString).toList());
            row.put("loadBalanced", service != null && service.loadBalanced());
            row.put("matches", counter == null ? 0L : counter.count());
            row.put("lastMatched", counter == null || counter.last() == null ? null
                    : counter.last().toString());
            rows.put(organization, row);
        });
        return rows;
    }

    /** Count a configured key against a real caller, and say so the first time it ever happens. */
    private void matched(String key) {
        Matches counter = matches.get(key);
        if (counter == null) {
            return; // not a configured key; nothing to credit and nothing to allocate
        }
        counter.hit();
        if (counter.announceOnce()) {
            log.info("tenant_upstream_first_match key={} upstream={} — a credential carrying this "
                            + "spelling reached the edge for the first time since startup. A key that "
                            + "never logs this line is one no credential carries: the tenant it names is "
                            + "still being served by the SHARED deployment. Check /actuator/gatewaytenants.",
                    key, properties.upstreams().get(key));
        }
    }

    private Snapshot resolve() {
        Map<String, ServiceDefinition> resolved = new LinkedHashMap<>();
        Set<String> unresolved = new LinkedHashSet<>();
        for (String serviceId : properties.upstreams().values()) {
            services.find(serviceId).ifPresentOrElse(
                    service -> resolved.put(serviceId, service),
                    () -> unresolved.add(serviceId));
        }
        return new Snapshot(Collections.unmodifiableMap(resolved), Collections.unmodifiableSet(unresolved));
    }

    /**
     * Log only what CHANGED. A {@link RefreshRoutesEvent} also arrives on Spring Cloud's periodic
     * heartbeat, so logging the whole table every time would bury the one line an operator needs under
     * a table they have already read.
     */
    private void report(Snapshot previous, Snapshot current) {
        if (previous != null && previous.equals(current)) {
            return;
        }
        Set<String> wasUnresolved = previous == null ? Set.of() : previous.unresolved();
        for (String serviceId : current.unresolved()) {
            if (!wasUnresolved.contains(serviceId)) {
                errorLog.error("tenant_upstream_unresolved service={} tenants={} — every organization "
                                + "mapped to it is refused 503 until a gateway.services entry with this "
                                + "id exists (or discovery registers one). No other tenant is affected.",
                        serviceId, organizationsFor(serviceId));
            }
        }
        for (String serviceId : wasUnresolved) {
            if (!current.unresolved().contains(serviceId)) {
                errorLog.warn("tenant_upstream_recovered service={} tenants={} — now serving",
                        serviceId, organizationsFor(serviceId));
            }
        }
        if (previous == null && !properties.upstreams().isEmpty()) {
            log.info("Tenant upstreams: {} organization key(s) mapped to a dedicated deployment; every "
                            + "other organization routes to the shared one. A key is matched against the "
                            + "token's organization ALIAS, the provider id INSIDE that claim, or the "
                            + "platform organization id an API key introspects to — NOT against "
                            + "platform.tenant_placement.org_id, which no credential carries. Confirm "
                            + "/actuator/gatewaytenants shows matches>0 for a tenant BEFORE flipping its "
                            + "placement row. {}",
                    properties.upstreams().size(), properties.upstreams());
        }
    }

    private Set<String> organizationsFor(String serviceId) {
        Set<String> organizations = new LinkedHashSet<>();
        properties.upstreams().forEach((organization, mapped) -> {
            if (mapped.equals(serviceId)) {
                organizations.add(organization);
            }
        });
        return organizations;
    }

    /** The resolved half of (config × registry) — swapped whole, never mutated in place. */
    private record Snapshot(Map<String, ServiceDefinition> resolved, Set<String> unresolved) {
    }

    /**
     * How often one configured key has matched a real credential, and when it last did. Written on the
     * request path, so a {@link LongAdder} rather than a contended counter, and one clock read only on
     * the rare dedicated path. Deliberately since-startup rather than durable: the question it answers
     * is about THIS gateway's running configuration, and a count carried across a config change would
     * report a key that used to match.
     */
    private static final class Matches {

        private final LongAdder count = new LongAdder();
        private final AtomicBoolean announced = new AtomicBoolean();
        private volatile Instant last;

        void hit() {
            count.increment();
            last = Instant.now();
        }

        boolean announceOnce() {
            return announced.compareAndSet(false, true);
        }

        long count() {
            return count.sum();
        }

        Instant last() {
            return last;
        }
    }

    /**
     * What to do with this request: leave it on the shared deployment, send it to the tenant's own, or
     * refuse it. {@code reason} is operator-facing and is never put on the wire — which organization is
     * misconfigured is not a caller's business (AGENTS §9).
     */
    record Decision(Kind kind, ServiceDefinition service, EdgeOrganization organization,
            String serviceId, String refusalCode, String reason) {

        enum Kind { SHARED, DEDICATED, REFUSED }

        /** Metric tag values — a fixed, tiny set, because a free-form reason on a tag is a cardinality bomb. */
        static final String UNRESOLVED_SERVICE = "unresolved_service";
        static final String AMBIGUOUS_CONFIG = "ambiguous_config";
        static final String UPSTREAM_UNREACHABLE = "upstream_unreachable";

        static Decision shared() {
            return new Decision(Kind.SHARED, null, null, null, null, null);
        }

        static Decision dedicated(ServiceDefinition service) {
            return new Decision(Kind.DEDICATED, service, null, service.id(), null, null);
        }

        static Decision refused(EdgeOrganization organization, String serviceId, String refusalCode,
                String reason) {
            return new Decision(Kind.REFUSED, null, organization, serviceId, refusalCode, reason);
        }

        boolean isDedicated() {
            return kind == Kind.DEDICATED;
        }

        boolean isRefused() {
            return kind == Kind.REFUSED;
        }

        /** How the organization is named in a log line — the alias when there is one, else the id. */
        String organizationName() {
            if (organization == null) {
                return "unknown";
            }
            return organization.alias() != null ? organization.alias() : organization.id();
        }
    }
}
