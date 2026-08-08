package ug.co.smsone.shared.cache;

import java.util.UUID;
import ug.co.smsone.shared.tenancy.Tenant;
import ug.co.smsone.shared.tenancy.TenantContext;

/**
 * Key helpers for {@link CacheScope#TENANT} caches, callable from a {@code @Cacheable} SpEL key
 * expression (which is why this type and its methods are public).
 *
 * <p><b>This is not a second separation mechanism.</b> {@link TwoLevelCache} prefixes the tenant into
 * the key on its own, from the thread's axis, and that is the only thing keeping two tenants' entries
 * apart. What lives here is the ASSERTION that the axis is the tenant the value is about — the one
 * assumption the prefix rests on and the one a key can no longer state for itself once the hand-rolled
 * organization has been taken out of it (ADR 0010 §3.5).
 *
 * <p>The distinction matters because the prefix comes from the AXIS while the value comes from a method
 * ARGUMENT. Those agree on every request path, where {@code CurrentUserFilter} pinned the very org the
 * handler then names. Where they could ever diverge, the cached answer would be the wrong tenant's with
 * no read to make it obvious — a cache hit performs no query, so the schema routing that would otherwise
 * catch it never runs. This turns that into a throw at the key, on hits as well as misses, which is the
 * only place both go through.
 */
public final class TenantCacheKeys {

    /**
     * The constant key for a cache holding exactly ONE entry per tenant — the tenant's own answer,
     * with nothing further to discriminate inside it ({@code org-entitlements} is the shape). The
     * value is arbitrary and never parsed; only its constancy matters, because the tenant prefix in
     * front of it is what makes the full key unique.
     */
    private static final String WHOLE_TENANT = "self";

    private TenantCacheKeys() {}

    /**
     * The key for an entry about {@code orgId} itself, on a cache whose entries are one-per-tenant.
     *
     * @throws IllegalStateException if the thread is not pinned to {@code orgId} — either no tenant
     *     axis at all, or a different one. Both would file the answer under the wrong tenant's prefix.
     */
    public static String forThisTenant(UUID orgId) {
        requireAxis(orgId);
        return WHOLE_TENANT;
    }

    /**
     * The same key without the axis check — for an evictor that names its organization explicitly to
     * {@code TwoLevelCacheManager.evictForTenant(...)} and therefore has no axis to check against. It
     * is the one legitimate way to reach a tenant's entry from a thread that cannot pin (an
     * after-commit listener runs in its own transaction, where a pin is the silent no-op
     * {@code TenantContext} throws to prevent).
     */
    public static String wholeTenant() {
        return WHOLE_TENANT;
    }

    private static void requireAxis(UUID orgId) {
        Tenant tenant = TenantContext.current();
        if (tenant instanceof Tenant.Org org && org.orgId().equals(orgId)) {
            return;
        }
        throw new IllegalStateException("A tenant-scoped cache entry for organization " + orgId
                + " was asked for on the axis " + describe(tenant) + ". The tenant in the cache key comes"
                + " from the thread's axis, so answering here would file (or read) this organization's"
                + " value under another one's prefix — and a cache HIT runs no query, so the schema"
                + " routing that normally catches a wrong axis never gets the chance. Pin the"
                + " organization before the call, outside any transaction (TenantContext.callAs).");
    }

    private static String describe(Tenant tenant) {
        return tenant instanceof Tenant.Org org ? "organization " + org.orgId()
                : tenant.getClass().getSimpleName().toUpperCase(java.util.Locale.ROOT);
    }
}
