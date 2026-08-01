package ug.co.smsone.subscription.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves an org's effective entitlement map (subscription's plan, or FREE when none), cached per
 * org. A separate bean from {@code EntitlementsImpl} for the same reason {@code PermissionResolver}
 * and {@code TranslationBundles} are: {@code @Cacheable} on a self-invoked method never reaches
 * the proxy. Values are wrapped so the map survives the L2 JSON round trip with nulls intact.
 */
@Component
class EntitlementResolver {

    static final String CACHE = "org-entitlements";
    /** L2-safe null stand-in: a JSON map cannot round-trip null VALUES distinguishably. */
    static final long FEATURE = Long.MIN_VALUE;

    private final OrgSubscriptionRepository subscriptions;
    private final PlanRepository plans;

    EntitlementResolver(OrgSubscriptionRepository subscriptions, PlanRepository plans) {
        this.subscriptions = subscriptions;
        this.plans = plans;
    }

    @Cacheable(cacheNames = CACHE, key = "#orgId.toString()", sync = true)
    @Transactional(readOnly = true)
    public Map<String, Long> resolve(UUID orgId) {
        Plan plan = subscriptions.findByOrgId(orgId)
                .flatMap(subscription -> plans.findById(subscription.getPlanId()))
                .or(() -> plans.findByCode("FREE"))
                .orElse(null);
        if (plan == null) {
            return Map.of(); // seeder not run (fresh empty DB mid-migration): fail toward FREE-less
        }
        Map<String, Long> effective = new LinkedHashMap<>();
        // Features are stored as negative sentinels (Hibernate drops null map VALUES on load);
        // normalized here so consumers see exactly one encoding.
        plan.getEntitlements().forEach((key, value) ->
                effective.put(key, value == null || value < 0 ? FEATURE : value));
        return effective;
    }

    Optional<Plan> planOf(UUID orgId) {
        return subscriptions.findByOrgId(orgId)
                .flatMap(subscription -> plans.findById(subscription.getPlanId()))
                .or(() -> plans.findByCode("FREE"));
    }
}
