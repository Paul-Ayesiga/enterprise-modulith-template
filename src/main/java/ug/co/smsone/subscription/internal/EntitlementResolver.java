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

    /**
     * One entry per tenant, and the tenant is no longer written into this key by hand.
     *
     * <p>It used to be {@code #orgId.toString()}, which was a hand-rolled tenant prefix in a cache that
     * {@code CacheRegistry} now declares {@link ug.co.smsone.shared.cache.CacheScope#TENANT} — so the
     * organization went into the key twice, by two mechanisms, and two mechanisms doing one job is how
     * one of them drifts. The registry's prefix is the one that stays, because it applies to every key
     * of every tenant-scoped cache rather than to the ones someone remembered.
     *
     * <p>What the hand-rolled half was quietly also doing is kept explicitly:
     * {@code forThisTenant(#orgId)} throws unless the thread is pinned to the very org being asked
     * about. The prefix comes from the axis and the value comes from this argument, so their agreement
     * is the assumption the whole scheme rests on — and it is an assumption a cache HIT would otherwise
     * never test, because a hit runs no query and never reaches the schema routing that catches a wrong
     * axis anywhere else.
     */
    private static final String KEY = "T(ug.co.smsone.shared.cache.TenantCacheKeys).forThisTenant(#orgId)";

    /** L2-safe null stand-in: a JSON map cannot round-trip null VALUES distinguishably. */
    static final long FEATURE = Long.MIN_VALUE;

    private final OrgSubscriptionRepository subscriptions;
    private final PlanRepository plans;

    EntitlementResolver(OrgSubscriptionRepository subscriptions, PlanRepository plans) {
        this.subscriptions = subscriptions;
        this.plans = plans;
    }

    @Cacheable(cacheNames = CACHE, key = KEY, sync = true)
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
