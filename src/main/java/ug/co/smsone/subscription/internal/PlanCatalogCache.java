package ug.co.smsone.subscription.internal;

import java.util.UUID;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.cache.StaleWhileUnreachable;
import ug.co.smsone.subscription.PlanSnapshot;

/**
 * The cached half of {@link PlanCatalogImpl}: {@code plan id/code → PlanSnapshot} out of
 * {@code platform.plan} + {@code platform.plan_entitlement}. A separate bean so the cached call goes
 * through the proxy — self-invocation would not — the same reason {@link EntitlementResolver} is its
 * own bean.
 *
 * <p><b>Two keys per plan, one namespace.</b> {@code id|<uuid>} and {@code code|<CODE>} live in the
 * one GLOBAL {@code plan-catalog} cache; {@code SubscriptionService} evicts BOTH on every plan
 * mutation via {@link #idKey}/{@link #codeKey}, which is why the key builders are package-visible
 * rather than private. Absences are not cached ({@code unless}), so a freshly created plan resolves on
 * the next read with nothing to evict.
 *
 * <p><b>The value is a record, and that shape is load-bearing:</b> L2 stores JSON with type ids, and
 * {@code ValkeyCacheIntegrationTest} pins the record-with-collection round trip this relies on. The
 * bare-UUID trap the resolution caches document does not apply — an OBJECT carries its type id; only a
 * scalar cannot.
 *
 * <p><b>The read is by qualified name and therefore axis-agnostic</b> — {@code Plan} declares
 * {@code @Table(schema = "platform")}, so a miss resolves from any axis co-located with the platform
 * database, which is every axis in Phase 7 ("today's behaviour unchanged" is this line). The day a
 * tenant's axis reaches a database whose minimal {@code platform} schema does not hold {@code plan}
 * (ADR 0011 §5.1), a cold miss on that axis fails loudly — the deliberate tripwire — and the repair is
 * Phase 8's adapter behind the same port, not a wider search_path.
 *
 * <p>The holdover refresh sits INSIDE the loaders (ADR 0011 §2.3): they run only on a genuine
 * authoritative read, never on a cache hit, so each entry's instant is exactly "when was this snapshot
 * last known true".
 */
@Component
class PlanCatalogCache {

    static final String CACHE = "plan-catalog";

    private final PlanRepository plans;
    private final StaleWhileUnreachable.Holdover<PlanSnapshot> holdover;

    PlanCatalogCache(PlanRepository plans, StaleWhileUnreachable stale) {
        this.plans = plans;
        this.holdover = stale.holdoverFor(CACHE);
    }

    @Cacheable(cacheNames = CACHE, key = "'id|' + #planId", unless = "#result == null")
    public PlanSnapshot byId(UUID planId) {
        return holdover.refresh(idKey(planId),
                plans.findById(planId).map(PlanCatalogCache::snapshot).orElse(null));
    }

    @Cacheable(cacheNames = CACHE, key = "'code|' + #code", unless = "#result == null")
    public PlanSnapshot byCode(String code) {
        return holdover.refresh(codeKey(code),
                plans.findByCode(code).map(PlanCatalogCache::snapshot).orElse(null));
    }

    /** The outage path — see {@link ug.co.smsone.subscription.PlanCatalog}'s contract. */
    PlanSnapshot lastKnownByIdOr(UUID planId, RuntimeException failure) {
        return holdover.serveOr(idKey(planId), failure);
    }

    /** The outage path — see {@link ug.co.smsone.subscription.PlanCatalog}'s contract. */
    PlanSnapshot lastKnownByCodeOr(String code, RuntimeException failure) {
        return holdover.serveOr(codeKey(code), failure);
    }

    /** Drops both remembered forms of one plan — the eviction companion, for mutation paths. */
    void forget(UUID planId, String code) {
        holdover.forget(idKey(planId));
        holdover.forget(codeKey(code));
    }

    /** The SpEL cache key as Java — the two must not drift, or refreshes go invisible to the outage path. */
    static String idKey(UUID planId) {
        return "id|" + planId;
    }

    /** @see #idKey */
    static String codeKey(String code) {
        return "code|" + code;
    }

    private static PlanSnapshot snapshot(Plan plan) {
        return new PlanSnapshot(plan.getId(), plan.getCode(), plan.getName(), plan.getEntitlements());
    }
}
