package ug.co.smsone.shared.cache;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.tenancy.TenantRoutes;
import ug.co.smsone.shared.tenancy.TenantSchemaFloor;

/**
 * What a promotion has to forget, and what it deliberately does not (ADR 0010 §6 hop 0→1, Phase 5).
 *
 * <h2>Why the flip needs an eviction at all</h2>
 *
 * <p>Promotion moves a tenant's rows from {@code tenant_pool} into {@code t_<32hex>} and then flips one
 * registry row. Every read that happens after the flip lands in the new schema — <em>except</em> the
 * reads that do not happen at all because a cache already holds their answer. Those answers were
 * computed against the OLD schema, and a cache HIT runs no query, so none of the machinery that
 * normally catches a wrong schema (the routing DataSource, {@code MappedSchemaValidator}, an
 * unqualified table that simply is not there) ever gets a chance to notice. One of those answers is an
 * authorization decision, which is why this is a required step of the runbook and not a tidiness one.
 *
 * <h2>When to call it: after the flip has COMMITTED, before the freeze is lifted</h2>
 *
 * <p>The ordering is not interchangeable and both wrong orders are reachable:
 *
 * <ul>
 *   <li><b>Before the flip</b> leaves a window in which any reader repopulates the very entries just
 *       dropped, from the old schema, and the repopulated entry then outlives the promotion by a full
 *       L2 TTL. Evicting early is not "extra safe", it is the bug.</li>
 *   <li><b>After the freeze is lifted</b> serves the old schema's answers to real traffic for however
 *       long the gap is. The freeze (an org-scoped {@code RESTRICT} maintenance window plus the queue
 *       and job pause) is what makes the eviction's own window empty; outside it there is no such
 *       guarantee.</li>
 * </ul>
 *
 * <p>It is idempotent and it names no schema, so calling it twice costs a re-resolve and nothing else,
 * and it is equally the right call on a <b>demotion</b> — reverse promotion moves the same rows the
 * other way and invalidates exactly the same answers.
 *
 * <p>It deliberately does <em>not</em> refuse to run inside a transaction, even though "before the flip
 * commits" is the failure above. An {@code @ApplicationModuleListener} on a promotion event is a
 * perfectly good caller and runs in a transaction of its own — <em>after</em> the flip committed, which
 * is the condition that matters; {@code SubscriptionCacheEvictor} is the same shape. The condition is
 * about the flip's commit, not about the evictor's, and no check here can tell them apart.
 *
 * <h2>The verdict for every declared cache, and why the GLOBAL six are not evicted</h2>
 *
 * <p>{@link #AT_THE_FLIP} classifies every name in {@link CacheRegistry} and
 * {@code TenantPromotionEvictionTest} fails the build when the two sets disagree — the same shape, and
 * for the same reason, as {@code CacheDeclarationTest} over the registry itself. Adding a cache is now
 * two decisions, not one: which tenancy axis its entries belong to, and whether a tenant changing
 * schema changes its value.
 *
 * <p>The six {@link Verdict#PROMOTION_INVARIANT} lines are not an optimization and they are not
 * laziness. Every one of them reads <em>platform-tier</em> tables (ADR 0010 §2), and promotion moves no
 * platform-tier row and changes no {@code organization.id} — the silo is NAMED after that id. Evicting
 * them anyway would cost the whole installation its edge translation caches on every promotion, and
 * would teach the next reader that a global cache is promotion-sensitive, which is false and is how the
 * eighth cache gets added to this list for a reason nobody can state. The invariance is a property of
 * the VALUE each one holds; each line below says which value, so that anyone who changes the value
 * shape is told, at the line, that the verdict changes with it.
 *
 * <p><b>ADR 0010 §6 originally named {@code OrgResolutionCache} ({@code org-by-external-id}) as the
 * cache the flip evicts, and that line is now corrected in the ADR.</b> It holds
 * {@code (provider, issuer, external_org_id) → organization.id}, read out of {@code platform
 * .external_organization} — which is to say it holds the ROUTER'S INPUT, not the router's answer.
 * Promotion changes where that org id routes TO; it cannot change the id. The memo that DOES hold the
 * router's answer is {@link TenantRoutes}, which is not a Spring cache at all and is therefore not in
 * the table below; it is dropped in {@link #evictAfterPlacementFlip} beside
 * {@code TenantSchemaFloor}'s. If {@code org-by-external-id}'s value ever grows to carry a schema name
 * or a datasource, it stops being a translation and becomes routing state, and its verdict flips to
 * {@link Verdict#CLEARED_FOR_EVERY_TENANT} the same day.
 *
 * <h2>The two TENANT caches are evicted by different mechanisms, and the asymmetry is Job 1's answer</h2>
 *
 * <p>{@code org-entitlements} holds exactly one entry per tenant under
 * {@link TenantCacheKeys#wholeTenant()}, and its key helper throws unless the thread's axis IS the org
 * being asked about — so one org's entitlements can only ever exist under one prefix, and a keyed
 * {@code evictForTenant} is provably complete. {@code org-permissions} is keyed
 * {@code <organizationId>:<personId>}: one entry per MEMBER, under a prefix that is the ASKING AXIS,
 * which for a fan-out over the pooled home is {@code TenantHome.pool()}'s non-org uuid and not the
 * organization at all. Neither the member set nor the set of prefixes is knowable from {@code shared},
 * so the only complete eviction is {@link org.springframework.cache.Cache#clear()} — coarse, spanning
 * every tenant, and exactly what {@code OrgPermissionCacheEvictor} already does on every membership
 * change. Coarse and correct beats precise and wrong, and a promotion is rarer than a role edit.
 */
@Component
public final class TenantPromotionCaches {

    private static final Logger log = LoggerFactory.getLogger(TenantPromotionCaches.class);

    /** What the flip does to one cache. */
    public enum Verdict {

        /**
         * Dropped for every tenant. The verdict for a {@link CacheScope#TENANT} cache whose keys are
         * not enumerable from here — because the entries are per-member, or because they can have been
         * written under a fan-out's shared axis rather than the tenant's own prefix.
         */
        CLEARED_FOR_EVERY_TENANT,

        /**
         * Dropped for the promoted tenant only. Available exactly when the cache holds one entry per
         * tenant under {@link TenantCacheKeys#wholeTenant()}, so naming the org reaches all of it.
         */
        EVICTED_FOR_THIS_TENANT,

        /**
         * Left alone: promotion cannot change what this cache holds. Only ever legitimate for a
         * {@link CacheScope#GLOBAL} cache over platform-tier tables — a TENANT cache reads tenant-tier
         * rows by definition, and tenant-tier rows are precisely what a promotion moves.
         */
        PROMOTION_INVARIANT
    }

    /**
     * The eight caches and what the flip does to each. Literals rather than the constants that declare
     * them, for the reason {@link CacheRegistry} gives at length: every cache name lives on a
     * package-private field inside its own module, which {@code shared} cannot see and must not be
     * given a reason to. The enumeration test closes the gap from the other side.
     */
    private static final Map<String, Verdict> AT_THE_FLIP = verdicts();

    private static Map<String, Verdict> verdicts() {
        Map<String, Verdict> flip = new LinkedHashMap<>();
        // organization.internal.PermissionResolver — membership, org_role and role_permission are all
        // tenant-tier and all move. This is the AUTHORIZATION answer, and it is why the runbook step
        // exists. Cleared rather than evicted by key: see the class note.
        flip.put("org-permissions", Verdict.CLEARED_FOR_EVERY_TENANT);
        // subscription.internal.EntitlementResolver — org_subscription is tenant-tier and moves. One
        // entry per tenant under TenantCacheKeys.wholeTenant(), so the promoted org's is reachable by
        // name and nobody else's entitlements are disturbed.
        flip.put("org-entitlements", Verdict.EVICTED_FOR_THIS_TENANT);
        // identity.internal.PersonResolutionCache — (provider, issuer, subject) -> person.id, out of
        // platform.external_identity and platform.person. Both platform-tier; a person does not move
        // when one of their organizations does, and person.id is stable across every hop in ADR §6.
        flip.put("person-by-subject", Verdict.PROMOTION_INVARIANT);
        // organization.internal.OrgResolutionCache — (provider, issuer, external_org_id) ->
        // organization.id, out of platform.external_organization. The router's INPUT, not its answer;
        // the silo is named after this very id. See the class note on ADR §6's corrected line.
        flip.put("org-by-external-id", Verdict.PROMOTION_INVARIANT);
        // settings.internal.SettingService — `setting` is platform-tier and has no org_id at all.
        flip.put("setting-values", Verdict.PROMOTION_INVARIANT);
        // settings.internal.FeatureFlagService — `feature_flag` is platform-tier with no org_id. The
        // per-org question over feature_flag_org_override (tenant-tier, and it DOES move) is
        // deliberately uncached, which is the only reason this line can say what it says.
        flip.put("feature-flags", Verdict.PROMOTION_INVARIANT);
        // localization.internal.TranslationBundles — keyed by locale; `translation` is platform-tier.
        flip.put("translation-bundles", Verdict.PROMOTION_INVARIANT);
        // subscription.internal.PlanCatalogCache — `plan` and `plan_entitlement` are platform-tier
        // catalog keyed by plan id/code; no entry names a tenant, a schema or a datasource, so a tenant
        // changing schema cannot change what any entry holds. The per-org answer derived FROM it
        // (org-entitlements) is the one the flip touches, above.
        flip.put("plan-catalog", Verdict.PROMOTION_INVARIANT);
        return Map.copyOf(flip);
    }

    private final TwoLevelCacheManager caches;
    private final TenantSchemaFloor floor;

    TenantPromotionCaches(TwoLevelCacheManager caches, TenantSchemaFloor floor) {
        this.caches = caches;
        this.floor = floor;
    }

    /**
     * The verdict for every declared cache — the promoter does not need this, the enumeration test
     * does. Immutable, and iteration order is the order of the reasoning above.
     */
    public static Map<String, Verdict> atTheFlip() {
        return AT_THE_FLIP;
    }

    /**
     * <strong>Forget everything this process resolved for {@code orgId} under its old schema.</strong>
     * One call, the whole set: the promoter runs it once, after the placement flip has committed and
     * while the freeze is still up.
     *
     * <p>Needs no tenant axis and no transaction. {@code clear()} names no key and can only
     * over-evict, and {@code evictForTenant} names its organization explicitly — both are the shapes
     * built for exactly this caller, a thread that has not entered (and must not enter) the tenant it
     * is reaching into. Every eviction broadcasts, so the other instances drop their L1 entries too;
     * a promotion that only fixed the promoting node would be no fix at all.
     *
     * <p>It also drops the two in-process memos derived from the very registry row the flip just
     * rewrote. Neither is a Spring cache; both are the same class of fact, and neither broadcasts.
     *
     * <ul>
     *   <li>{@link TenantSchemaFloor}'s remembered admission, whose "at or above the floor" entry never
     *       expires. The sanctioned sequence migrates the silo to head before flipping, so the memo
     *       would stay true — but "the sanctioned sequence gets it right" is the assumption a promotion
     *       runbook exists to stop relying on, and re-reading one row per promoted tenant costs
     *       nothing.</li>
     *   <li>{@link TenantRoutes}' memoized home — the memo that decides where this tenant's next
     *       statement LANDS rather than what it answers, and therefore the one that must not be stale.
     *       Dropping it fixes this process only; every other one re-reads within
     *       {@link TenantRoutes#routeTtl()}, which is why {@code TenantPromoter} holds the freeze that
     *       long after the flip before it deletes the source rows.</li>
     * </ul>
     *
     * @throws IllegalArgumentException if {@code orgId} is null — there is no "the tenant we just
     *     promoted" this class can infer, and silently clearing everything would hide the caller's bug.
     */
    public void evictAfterPlacementFlip(UUID orgId) {
        if (orgId == null) {
            throw new IllegalArgumentException("evictAfterPlacementFlip needs the organization whose"
                    + " placement moved; null names no tenant and no schema");
        }
        AT_THE_FLIP.forEach((cacheName, verdict) -> {
            switch (verdict) {
                case CLEARED_FOR_EVERY_TENANT -> caches.getCache(cacheName).clear();
                case EVICTED_FOR_THIS_TENANT ->
                        caches.evictForTenant(cacheName, orgId, TenantCacheKeys.wholeTenant());
                // Named and skipped rather than absent from the map: the enumeration test requires
                // every declared cache to appear here, so "we thought about it" is recorded as data.
                case PROMOTION_INVARIANT -> { }
            }
        });
        floor.forget(orgId);
        // The ROUTE itself, which is the one memo that decides where this tenant's next statement lands
        // rather than merely what it answers. Local to this process, like the floor's — every other one
        // re-reads within TenantRoutes.routeTtl(), which is why the promoter holds the freeze that long
        // after the flip before it deletes anything.
        TenantRoutes.forget(orgId);
        log.info("Tenant {} changed schema: dropped every cached answer resolved under its old one, and"
                + " this process's memoized route to it (ADR 0010 §6 hop 0->1). Do not lift the freeze"
                + " before this line.", orgId);
    }
}
