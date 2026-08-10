package ug.co.smsone.gateway.route;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Which organizations have a deployment of their own ({@code gateway.tenancy.*}) — ADR 0010 §7 Phase 8.
 *
 * <pre>
 * gateway:
 *   tenancy:
 *     upstreams:
 *       acme: modulith-acme          # organization name (as the token spells it) -> a gateway.services id
 *       "01H8…": modulith-acme       # the same tenant's other spelling, same upstream
 *     retry-after: PT30S
 * </pre>
 *
 * <h2>Why this is configuration and not a query</h2>
 *
 * <p>{@code platform.tenant_placement} is the platform's own answer to "where does this tenant live",
 * and it was the obvious candidate. It is the wrong source HERE for three reasons, in increasing order
 * of severity.
 *
 * <p><b>It is keyed on something the edge cannot see.</b> That table keys on {@code organization.id} —
 * a value only {@code external_organization} can produce from a token's claim. The gateway would have
 * to read two business tables to answer one routing question, and ADR 0007's hard rules forbid it a
 * business database at all: an edge holding tenant state stops being an edge.
 *
 * <p>Which is also the trap in the OTHER direction, and the one that actually bites: because
 * {@code tenant_placement.org_id} is the only spelling written down anywhere on the platform side, it is
 * the value an operator copies into the map above — where it matches no JWT, and the tenant is quietly
 * served by the shared deployment. Configure the spellings a CREDENTIAL carries. {@link TenantUpstreams}
 * counts what each key matches so that mistake is visible instead of silent.
 *
 * <p><b>It would put a blocking JDBC read on a reactive request path.</b> The gateway is WebFlux; the
 * platform is servlet + blocking JDBC. ADR 0007 says adapters integrate over network contracts for
 * exactly this reason, so the honest version of "read the table" is an HTTP hop to the modulith.
 *
 * <p><b>And that hop would make the platform a single point of failure for the one tenant that pays to
 * not have one.</b> A tenant gets its own deployment so that it survives the platform's outages — its
 * own database (ADR 0011), its own infrastructure tables, its own object-store root. Routing it through
 * a live lookup against the platform hands all of that back: primary goes down, the edge cannot answer
 * "where does acme live", and acme goes dark beside a database that was healthy the whole time. A
 * cached lookup only shortens the window, and ADR 0011 §2.4 has already ruled that placement is the one
 * fact that may NEVER be served stale — so the cache could not legally cover the outage either.
 *
 * <p><b>The cost, stated rather than hidden:</b> a cutover needs this file changed and the gateway
 * rolled, so placement can disagree with routing for the length of a deploy. That is the right shape of
 * cost. A tenant's own deployment is not a row that flips inside a transaction — it is a new deployable,
 * a new database, a fresh {@code event_publication}/{@code shedlock}/{@code idempotency_key}, a new
 * Valkey prefix and a new object-store root (ADR 0010 §6 hop 2→3). Every one of those is already a
 * deploy-time change, and §6 calls the hop "mostly configuration". The runbook orders it: flip
 * {@code tenant_placement} LAST, after this route exists, and the disagreement window never points at a
 * database that no longer holds the tenant's rows. What must not happen is the disagreement being
 * SILENT, which is what {@link TenantUpstreams} exists to prevent.
 *
 * @param upstreams organization name (alias, provider id, or the platform organization id an API key
 *        introspects to) → the {@code gateway.services} id that serves it. Many names may point at one
 *        service; a tenant with no entry is served by the shared deployment, which is every tenant today.
 * @param retryAfter the {@code Retry-After} a per-tenant refusal carries. Matches the platform's own
 *        {@code TenantSchemaFloor} shape, because it makes the same claim — this organization cannot be
 *        served right now, nothing is lost, retry.
 */
@ConfigurationProperties("gateway.tenancy")
public record TenantUpstreamProperties(Map<String, String> upstreams, Duration retryAfter) {

    public TenantUpstreamProperties {
        // MALFORMED config fails at startup; config that merely names something absent does not. That is
        // ADR 0011 §4.2's split, kept: a blank key or value cannot be repaired by anything happening
        // later and is a half-written cutover, whereas a service id nobody has registered yet is one
        // discovery event or one release away from correct — so THAT one is refused per tenant
        // (TenantUpstreams) rather than taking the whole edge down for every other tenant.
        Map<String, String> validated = new LinkedHashMap<>();
        if (upstreams != null) {
            upstreams.forEach((organization, serviceId) -> {
                if (organization == null || organization.isBlank()) {
                    throw new IllegalArgumentException(
                            "gateway.tenancy.upstreams has an entry with a blank organization name");
                }
                if (serviceId == null || serviceId.isBlank()) {
                    throw new IllegalArgumentException("gateway.tenancy.upstreams['" + organization
                            + "'] names no service — give it a gateway.services id, or remove the entry");
                }
                validated.put(organization, serviceId);
            });
        }
        // unmodifiableMap over a LinkedHashMap, NOT Map.copyOf: the admin table and the startup log
        // read as configured, and Map.copyOf randomizes iteration order.
        upstreams = Collections.unmodifiableMap(validated);
        // Sub-second is checked, not just zero and negative: Retry-After is expressed in whole seconds,
        // so PT0.5S would render as `Retry-After: 0` — an instruction to hammer a deployment that is
        // already failing to accept connections.
        retryAfter = retryAfter == null || retryAfter.toSeconds() < 1 ? Duration.ofSeconds(30) : retryAfter;
    }

    /** Seconds for the {@code Retry-After} header — the wire spelling of {@link #retryAfter()}. */
    public long retryAfterSeconds() {
        return retryAfter.toSeconds();
    }
}
