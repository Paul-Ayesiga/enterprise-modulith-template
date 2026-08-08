package ug.co.smsone.shared.cache;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Every cache name in the installation, and the tenancy axis each one's entries belong to
 * (ADR 0010 §3.5). {@link TwoLevelCacheManager#getCache} resolves a name through here and
 * <strong>fails</strong> on one that is not listed.
 *
 * <h2>Why a registry, and why failing is the whole point</h2>
 *
 * <p>The exposure this closes was never today's keys — it is that a cache manager which creates any
 * name on demand makes the NEXT {@code @Cacheable} over an org-scoped table leak silently. Nothing in
 * the type system distinguishes {@code @Cacheable("feature-flags")} from
 * {@code @Cacheable("org-tickets")}: both compile, both run, and only the second serves one tenant's
 * rows to another — after L1's 60 s TTL, which gives it the same intermittent signature
 * {@code PersonResolutionCache}'s javadoc already documents for the UUID/JSON trap. So the classification
 * cannot be optional. A name absent from this map is not "probably global", it is unclassified, and
 * unclassified fails loudly at the first {@code getCache} rather than quietly at the first cross-tenant
 * read.
 *
 * <p><b>The map is literals, not constants pulled from the declaring classes, and that is deliberate.</b>
 * Every cache name lives on a package-private constant inside its own module ({@code PermissionResolver
 * .CACHE}, {@code SettingService.VALUES_CACHE}), which {@code shared} cannot see and must not be given
 * a reason to. {@code CacheDeclarationTest} closes the gap the other way: it reads every
 * {@code cacheNames} value out of the compiled annotations and asserts the two sets are EQUAL — so a new
 * cache fails the build until it is classified here, and a name that stops being used fails it until the
 * stale line goes.
 *
 * <h2>Adding a cache</h2>
 *
 * <p>Declare it here first. {@link CacheScope#TENANT} is the answer unless the value is genuinely one
 * answer for the whole installation — see {@link CacheScope#GLOBAL} for the two shapes that qualify —
 * and a {@code GLOBAL} line owes the reader the argument, next to it, in the comment.
 */
public final class CacheRegistry {

    /**
     * The seven caches, with the ADR §3.5 verdict for each.
     *
     * <p>The two {@code GLOBAL} resolution caches are the load-bearing ones: they are read at the edge
     * BEFORE any tenant is known — they are how the tenant becomes known — so a tenant prefix on them
     * would need the answer it is prefixing. Both are safe unprefixed for the same structural reason:
     * their keys are {@code provider|issuer|<id from the token>}, an identifier the issuer already
     * guarantees unique across every tenant, and both cache a translation rather than a decision (a
     * stale entry can only name a tenant whose grants then resolve to nothing).
     */
    private static final Map<String, CacheScope> DECLARED = declarations();

    private static Map<String, CacheScope> declarations() {
        Map<String, CacheScope> declared = new LinkedHashMap<>();
        // identity.internal.PersonResolutionCache — "who is this login". Asked before a tenant exists.
        declared.put("person-by-subject", CacheScope.GLOBAL);
        // organization.internal.OrgResolutionCache — "which tenant is this claim". Same, and it IS the
        // step that produces the axis every TENANT cache below then depends on.
        declared.put("org-by-external-id", CacheScope.GLOBAL);
        // settings.internal.SettingService — `setting` has no org_id: one row, one answer, installation-wide.
        declared.put("setting-values", CacheScope.GLOBAL);
        // settings.internal.FeatureFlagService — `feature_flag` has no org_id either. The per-org
        // question (`isEnabledFor(key, orgId)`, over feature_flag_org_override) stays deliberately
        // uncached, which is what keeps this name honestly global.
        declared.put("feature-flags", CacheScope.GLOBAL);
        // localization.internal.TranslationBundles — keyed by locale; `translation` has no org_id.
        declared.put("translation-bundles", CacheScope.GLOBAL);
        // organization.internal.PermissionResolver — membership/org_role/role_permission are tenant-tier.
        declared.put("org-permissions", CacheScope.TENANT);
        // subscription.internal.EntitlementResolver — org_subscription is tenant-tier.
        declared.put("org-entitlements", CacheScope.TENANT);
        return Map.copyOf(declared);
    }

    private final Map<String, CacheScope> scopes;

    private CacheRegistry(Map<String, CacheScope> scopes) {
        this.scopes = Map.copyOf(scopes);
    }

    /** The declarations this application runs with. */
    public static CacheRegistry standard() {
        return new CacheRegistry(DECLARED);
    }

    /**
     * The standard declarations plus {@code extra} — the seam a test uses to declare a scratch cache
     * of its own (the serialization probes in {@code ValkeyCacheIntegrationTest} are the reason it
     * exists). Production code has no business calling it: a cache the application actually uses
     * belongs in {@link #DECLARED}, where {@code CacheDeclarationTest} can see it.
     *
     * @throws IllegalArgumentException if {@code extra} redeclares a standard name with a different
     *     scope — a test that could silently reclassify {@code org-permissions} as GLOBAL would be a
     *     test that proves nothing.
     */
    public static CacheRegistry standardPlus(Map<String, CacheScope> extra) {
        Map<String, CacheScope> merged = new LinkedHashMap<>(DECLARED);
        extra.forEach((name, scope) -> {
            CacheScope standard = DECLARED.get(name);
            if (standard != null && standard != scope) {
                throw new IllegalArgumentException("cache '" + name + "' is declared " + standard
                        + " by the application and cannot be redeclared " + scope);
            }
            merged.put(name, scope);
        });
        return new CacheRegistry(merged);
    }

    /**
     * The axis {@code cacheName}'s entries belong to.
     *
     * @throws IllegalArgumentException if the name is not declared. Read the class note before adding
     *     it: the failure is the feature.
     */
    public CacheScope scopeOf(String cacheName) {
        CacheScope scope = scopes.get(cacheName);
        if (scope == null) {
            throw new IllegalArgumentException("Cache '" + cacheName + "' is not declared in CacheRegistry."
                    + " Every cache declares its tenancy axis — " + CacheScope.TENANT + " (the tenant is"
                    + " prefixed into the key at both levels) or " + CacheScope.GLOBAL + " (one answer for"
                    + " the whole installation) — because an unclassified cache is the one that serves one"
                    + " tenant's rows to another. Declare it in CacheRegistry, TENANT unless the value is"
                    + " genuinely installation-wide. Declared: " + declaredNames());
        }
        return scope;
    }

    /** Whether {@code cacheName} is classified at all — {@link #scopeOf} without the throw. */
    public boolean declares(String cacheName) {
        return scopes.containsKey(cacheName);
    }

    /** Every declared name, sorted, for messages and for the enumeration test. */
    public Set<String> declaredNames() {
        return new TreeSet<>(scopes.keySet());
    }
}
