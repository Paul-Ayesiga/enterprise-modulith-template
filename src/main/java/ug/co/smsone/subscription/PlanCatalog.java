package ug.co.smsone.subscription;

import java.util.Optional;
import java.util.UUID;

/**
 * Read access to the plan catalog across the tier boundary — <b>the fourth port</b> (ADR 0011 §6,
 * after {@code PersonLookup}, {@code OrgLookup} and {@code OrgAuthorization}), in this module's API
 * package on the {@code apikeys.ApiKeyReminting} precedent: the capability crosses a boundary, the
 * data stays owned. {@code org_subscription} is tenant-tier and moves with its tenant; {@code plan}
 * and {@code plan_entitlement} are platform catalog and stay — so every read that used to join the two
 * in-process goes through here instead, and the join is impossible to reintroduce without being seen.
 *
 * <h2>The contract, which Phase 8's HTTP adapter implements unchanged</h2>
 *
 * <ul>
 *   <li><b>Reads are cacheable</b>: a GLOBAL {@code plan-catalog} cache, 60 s freshness TTL, evicted
 *       by key on every plan mutation through {@code SubscriptionService}. Plans are slow-moving
 *       catalog data: a downgrade landed at minute zero may be exercised at its old limits for up to
 *       the TTL, and an upgrade is invisible for the same window — accepted and bounded.</li>
 *   <li><b>Absence is an answer</b> (ADR 0011 §2.2): an unknown id or code returns empty, immediately,
 *       and replaces anything a holdover kept. Empty is how {@code EntitlementResolver} reaches its
 *       FREE fallback; it is never how an outage presents.</li>
 *   <li><b>Unreachable serves stale, bounded, then THROWS — never empty</b>: while the catalog's
 *       database cannot be asked (a connection answer, never a data answer), the last-known snapshot is
 *       served up to the {@code app.tenancy.identity-stale.ceiling} (PT15M), each entry aged from its
 *       own last successful refresh. At the ceiling the port throws
 *       {@code shared.cache.PlatformUnreachableException} — 503 + Retry-After on the wire. The
 *       fall-through it forbids: "no plan" reads as every quota unlimited
 *       ({@code EntitlementsImpl.limitOf} → null → {@code requireWithinLimit} passes), so a plan that
 *       cannot be known must never be a plan with no limits. Fail closed on quota, exactly as on
 *       authorization — the failure just wears a different status code.</li>
 * </ul>
 *
 * <p><b>Two methods, deliberately.</b> {@link #planByCode} exists because the FREE fallback is part of
 * the resolution semantics — an org with no subscription row runs on FREE — not a caller convenience.
 * Codes are the catalog's canonical upper-case identities; no normalization happens here.
 *
 * <p><b>Writes do not pass through this port.</b> Assigning a plan, trialing one, editing the catalog —
 * those are the platform's own audited paths on {@code PlanRepository}, built on authoritative reads,
 * never on a cached snapshot.
 */
public interface PlanCatalog {

    /** The plan behind an {@code org_subscription.plan_id} soft reference, or empty if none holds it. */
    Optional<PlanSnapshot> plan(UUID planId);

    /** The plan behind a canonical code ({@code FREE}, {@code PRO}, …), or empty if none carries it. */
    Optional<PlanSnapshot> planByCode(String code);
}
