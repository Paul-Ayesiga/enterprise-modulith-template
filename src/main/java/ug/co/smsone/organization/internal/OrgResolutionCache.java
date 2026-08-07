package ug.co.smsone.organization.internal;

import java.util.UUID;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import ug.co.smsone.organization.OrganizationDeleted;

/**
 * The cached half of {@link OrgResolver}: {@code (provider, issuer, external_org_id) → organization.id},
 * the tenant translation every authenticated human request performs. A separate bean so the cached call
 * goes through the proxy — self-invocation would not — the same reason {@link PermissionResolver} is its
 * own bean.
 *
 * <p><b>Only the id-keyed direction is cached, and that is the whole design.</b> On
 * {@link ExternalOrganization} both {@code external_org_id} and {@code organization_id} are
 * {@code updatable = false}, so this mapping is immutable for the life of the link. The ALIAS-keyed
 * fallback is deliberately left uncached in {@link OrgResolver}: an alias is mutable by design
 * (Keycloak's {@code organization} claim is keyed by it, so a rename over there has to be followable),
 * which means a freed alias can be taken by a DIFFERENT tenant — and a stale {@code alias → org} entry
 * is the one shape of this cache that could hand a caller a tenant that no longer owns the name. An
 * immutable key is cacheable; a reusable one is not.
 *
 * <p><b>It caches a translation, never a decision.</b> The entry says which tenant a provider id names,
 * not what the caller may do there: permissions still come from {@link PermissionResolver}, which
 * re-checks {@code organization.status} inside its own (separately evicted) value. A stale entry
 * therefore fails closed — it can only ever name a tenant whose grants resolve to nothing.
 *
 * <p><b>Invalidation, in full — and it is short precisely because the key is immutable:</b>
 * <ul>
 *   <li>absences are NOT cached ({@code unless = "#result == null"}), so a tenant linked for the first
 *       time resolves on the next request with nothing to evict;</li>
 *   <li>{@link OrgResolver#linkKeycloakOrg} needs no eviction: it either inserts a link (there was no
 *       entry to be stale, per the line above) or re-aliases one — and a re-alias touches neither half
 *       of this key. That is the whole reason the alias is not in it;</li>
 *   <li>{@code OrganizationDeleted} clears it here, AFTER COMMIT ({@code @ApplicationModuleListener}),
 *       so cached translations never outlive the tenant and a provider id can be re-linked to a new
 *       {@code organization} row without the old answer surviving.</li>
 * </ul>
 *
 * <p><b>The cached value is the id AS TEXT, and that is not a style choice.</b> L2 stores JSON, and a
 * JSON SCALAR cannot carry a type id the way an object can — a cached {@code UUID} reads back from
 * Valkey as a {@code String}, which would ClassCastException inside the CGLIB proxy on every
 * authenticated request, but only once L1 had expired. {@code ValkeyCacheIntegrationTest} pins both
 * halves of that; {@code identity.internal.PersonResolutionCache} carries the same note for the same
 * reason.
 */
@Component
class OrgResolutionCache {

    static final String CACHE = "org-by-external-id";

    /**
     * Strings only, because the L2 key has to render as text; '|' rather than ':' because an issuer is a
     * URL full of colons. Both components are non-null by the time {@link OrgResolver} calls — it derives
     * the provider and rejects a blank id first.
     */
    private static final String KEY = "#provider.name() + '|' + #issuer + '|' + #externalOrgId";

    private final ExternalOrganizationRepository links;

    OrgResolutionCache(ExternalOrganizationRepository links) {
        this.links = links;
    }

    /**
     * Public for the same reason {@link PermissionResolver#resolve} is: caching advice is applied by a
     * proxy, and a public method is the shape that is advised whatever proxying strategy is in force.
     *
     * <p>No {@code @Transactional}: the read is a single statement the repository already runs read-only,
     * so a cache HIT costs the edge no connection checkout at all.
     */
    @Cacheable(cacheNames = CACHE, key = KEY, unless = "#result == null")
    public String organizationIdTextOf(OrgProvider provider, String issuer, String externalOrgId) {
        return links.organizationIdByExternalOrgId(provider, issuer, externalOrgId)
                .map(UUID::toString).orElse(null);
    }

    /**
     * Coarse clear-all rather than a keyed evict, because the caller knows the tenant and not the
     * provider ids that name it. Same trade-off, and the same reasoning, as
     * {@link OrgPermissionCacheEvictor}: correct and simple beats precise and wrong, and the two-level
     * cache broadcasts the invalidation to the other instances' L1.
     */
    @CacheEvict(cacheNames = CACHE, allEntries = true)
    public void forgetAll() {
        // The eviction IS the behaviour; the annotation does the work.
    }

    /**
     * Carries its own {@code @CacheEvict} rather than calling {@link #forgetAll()}: that call would be
     * self-invocation, which bypasses the proxy and would evict nothing at all (AGENTS §4.3).
     */
    @ApplicationModuleListener
    @CacheEvict(cacheNames = CACHE, allEntries = true)
    void onOrganizationDeleted(OrganizationDeleted event) {
        // Cached translations must not outlive the tenant.
    }
}
