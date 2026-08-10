package ug.co.smsone.subscription;

import java.util.Map;
import java.util.UUID;

/**
 * One plan as the catalog answers for it — the immutable value {@link PlanCatalog} returns and caches,
 * and the exact shape Phase 8's wire adapter serializes (ADR 0011 §6).
 *
 * <p><b>The entitlement encoding is the STORED one, and that is a wire decision, not a leak.</b> A key
 * present with a NEGATIVE value is feature-on; a key present with a non-negative value is a numeric
 * cap; an absent key is feature-off / unlimited. The natural encoding (null value = feature-on) cannot
 * travel: Hibernate drops null map values on load and a JSON map cannot round-trip null values
 * distinguishably — the same two facts that put the sentinel into storage put it here, and an HTTP
 * adapter inherits a map that survives serialization unchanged. Consumers normalize at their edge,
 * exactly as {@code EntitlementResolver} and the subscription views do today.
 *
 * <p>{@code code} is the plan's immutable identity (upper-case, e.g. {@code FREE}); {@code name} and
 * the entitlements are the mutable facets an operator edits. {@code id} is what
 * {@code org_subscription.plan_id} soft-references across the tier boundary — the join ADR 0010 §6 cut
 * the FK out of, and the reason this type exists at all.
 */
public record PlanSnapshot(UUID id, String code, String name, Map<String, Long> entitlements) {

    public PlanSnapshot {
        entitlements = Map.copyOf(entitlements);
    }
}
