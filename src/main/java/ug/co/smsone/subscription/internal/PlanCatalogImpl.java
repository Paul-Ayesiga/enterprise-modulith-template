package ug.co.smsone.subscription.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ug.co.smsone.subscription.PlanCatalog;
import ug.co.smsone.subscription.PlanSnapshot;

/**
 * Implements the {@link PlanCatalog} port against {@link PlanRepository} — Phase 7's in-process
 * adapter; Phase 8 swaps in an HTTP one with no contract change (ADR 0011 §6, §9).
 *
 * <p>Thin by design, like {@code PersonLookupImpl}: the caching and the stale-while-unreachable
 * semantics are {@link PlanCatalogCache}'s, because they are facts about how the catalog is read, and
 * this seam only decides WHAT the port promises. The try/catch is the outage path: it must sit here,
 * outside the {@code @Cacheable} proxy, so a stale answer is never written back into the cache as
 * fresh — restarting a freshness TTL on a stale value is how a 15-minute ceiling quietly compounds.
 */
@Component
class PlanCatalogImpl implements PlanCatalog {

    private final PlanCatalogCache cache;

    PlanCatalogImpl(PlanCatalogCache cache) {
        this.cache = cache;
    }

    @Override
    public Optional<PlanSnapshot> plan(UUID planId) {
        if (planId == null) {
            return Optional.empty();
        }
        PlanSnapshot snapshot;
        try {
            snapshot = cache.byId(planId);
        } catch (RuntimeException failure) {
            snapshot = cache.lastKnownByIdOr(planId, failure);
        }
        return Optional.ofNullable(snapshot);
    }

    @Override
    public Optional<PlanSnapshot> planByCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        PlanSnapshot snapshot;
        try {
            snapshot = cache.byCode(code);
        } catch (RuntimeException failure) {
            snapshot = cache.lastKnownByCodeOr(code, failure);
        }
        return Optional.ofNullable(snapshot);
    }
}
