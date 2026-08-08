package ug.co.smsone.shared.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ug.co.smsone.organization.MemberRemoved;
import ug.co.smsone.organization.Permission;
import ug.co.smsone.shared.security.OrgAuthorization;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.shared.tenancy.TenantHome;
import ug.co.smsone.shared.tenancy.TenantSchemaFloor;
import ug.co.smsone.shared.tenancy.TenantSchemas;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;
import ug.co.smsone.testsupport.TenantSilos;

/**
 * <b>What a promotion has to forget</b> (ADR 0010 §6 hop 0→1, Phase 5) — and, in the last two tests,
 * the two rules the cache keys themselves rest on.
 *
 * <h2>Why the eviction is the sharp end of promotion</h2>
 *
 * <p>Every other step of a promotion is verifiable by looking: the schema exists, the migrations ran,
 * the row counts match, the placement row says the new home. The eviction is the only step whose
 * failure is invisible — nothing errors, no schema is missing, no count is off. A cached permission set
 * simply keeps being served, computed from a schema the tenant has left, and it keeps being served for
 * an L2 lifetime on every node at once. It is an AUTHORIZATION answer, so a missed eviction here is the
 * AGENTS §5.5 failure ("a revoked membership must deny the very next request") arriving through a door
 * §5.5 was not watching.
 *
 * <p>So each test below is written to fail if the corresponding eviction is removed, and the
 * intermediate assertions exist to make that falsifiable: a test that only checked "empty afterwards"
 * would pass just as happily against a cache that was never populated.
 */
class TenantPromotionEvictionTest extends AbstractIntegrationTest {

    private static final String PERMISSIONS = "org-permissions";
    private static final String ENTITLEMENTS = "org-entitlements";
    private static final String ORG_BY_EXTERNAL_ID = "org-by-external-id";

    /**
     * Registered so the silos this class builds are swept after every test — see the fixture's class
     * note on why a leaked {@code t_<32hex>} fails other files rather than this one. Non-static, which
     * is required: it captures the Spring context in {@code beforeEach}.
     */
    @RegisterExtension
    final TenantSilos silos = new TenantSilos();

    @Autowired
    private TenantPromotionCaches promotionCaches;

    @Autowired
    private TwoLevelCacheManager caches;

    @Autowired
    private OrgAuthorization authorization; // the port PermissionResolver's cache sits behind

    @Autowired
    private TenantSchemaFloor schemaFloor;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private ApplicationEventPublisher events;

    // ---------------------------------------------------------------- the classification is complete

    /**
     * The gate, and the reason this file is worth more than the class it tests: a cache added later
     * fails the build until someone has decided what a promotion does to it.
     *
     * <p>Compared against {@code CacheRegistry.standard()} rather than the injected bean, because a test
     * may legitimately declare scratch caches through {@code standardPlus} ({@code
     * ValkeyCacheIntegrationTest} does) and a probe name has no promotion verdict to give.
     */
    @Test
    void everyDeclaredCacheSaysWhatAPromotionDoesToIt() {
        assertThat(TenantPromotionCaches.atTheFlip().keySet())
                .describedAs("""
                        TenantPromotionCaches must classify exactly the caches CacheRegistry declares.
                        A name declared but NOT classified here is a cache nobody decided about at the \
                        flip: a promotion leaves its entries in place, still answering from the schema \
                        the tenant has left, and nothing fails while it does (ADR 0010 §6 hop 0->1).
                        A name classified but NOT declared is a stale line — delete it.""")
                .containsExactlyInAnyOrderElementsOf(CacheRegistry.standard().declaredNames());
    }

    /**
     * The semantic half of the same gate. {@code PROMOTION_INVARIANT} is a claim that a tenant changing
     * schema cannot change the value — which is only ever true of a value read from platform-tier
     * tables. A {@link CacheScope#TENANT} cache reads the tenant's own rows by definition, and those
     * rows are precisely what a promotion moves, so the combination is a contradiction rather than a
     * judgement call and does not need a human to catch it.
     */
    @Test
    void noTenantScopedCacheMayClaimToBePromotionInvariant() {
        CacheRegistry registry = CacheRegistry.standard();
        assertThat(TenantPromotionCaches.atTheFlip())
                .describedAs("a TENANT cache holds answers computed from tenant-tier rows, and a"
                        + " promotion moves exactly those rows to another schema")
                .noneSatisfy((name, verdict) -> {
                    assertThat(registry.scopeOf(name)).isEqualTo(CacheScope.TENANT);
                    assertThat(verdict).isEqualTo(TenantPromotionCaches.Verdict.PROMOTION_INVARIANT);
                });
    }

    // ------------------------------------------------------------------------- the authorization answer

    /**
     * <b>The one that matters.</b> A permission set resolved before the flip is a decision computed
     * against the old schema; after the flip the same question would read a different one. The middle
     * assertion is the proof that this test can fail: it shows the stale answer still being served,
     * which is exactly the state the eviction removes and the state a promotion without it would ship.
     *
     * <p>The membership is deleted with raw SQL and no event on purpose. The ordinary eviction path
     * ({@code OrgPermissionCacheEvictor}, on {@code MemberRemoved}) is what keeps §5.5 true for a change
     * the application makes; a promotion changes no membership row at all — it moves them — so nothing
     * on that path fires, and this is the shape of divergence the flip has to cover on its own.
     */
    @Test
    void theFlipDropsAPermissionSetThatWasResolvedBeforeIt() {
        UUID orgId = organization();
        UUID personId = EdgeSeed.person(jdbc, "kc-" + UUID.randomUUID());
        UUID roleId = EdgeSeed.member(jdbc, orgId, personId, "ADMIN");
        grant(orgId, roleId, Permission.ORG_DELETE);

        assertThat(authorization.permissions(personId, orgId))
                .describedAs("primed: the answer is now cached under this tenant's prefix")
                .containsExactly(Permission.ORG_DELETE.code());

        TenantContext.runAs(orgId, () -> jdbc.update(
                "delete from membership where org_id = ? and person_id = ?", orgId, personId));

        assertThat(authorization.permissions(personId, orgId))
                .describedAs("the cache is still answering from before the row went — without this the"
                        + " assertion below would pass against a cache that was never populated")
                .containsExactly(Permission.ORG_DELETE.code());

        promotionCaches.evictAfterPlacementFlip(orgId);

        assertThat(authorization.permissions(personId, orgId))
                .describedAs("after the flip the answer comes from the database again, and the database"
                        + " no longer grants it")
                .isEmpty();
    }

    /**
     * The prefix an {@code org-permissions} entry is written under is the ASKING AXIS, and a background
     * sweep asks under {@code TenantHome.pool()} — one axis for every unpromoted tenant. Such an entry
     * is unreachable by any key naming the organization, which is why the flip's verdict for this cache
     * is a whole-cache {@code clear()} and not a keyed evict. This is the test that fails if someone
     * "tightens" that to {@code evictForTenant}.
     */
    @Test
    void aPermissionEntryWrittenUnderAHomesAxisIsDroppedToo() {
        UUID orgId = organization();
        UUID personId = UUID.randomUUID();
        UUID homeAxis = TenantHome.pool().axis();
        String key = orgId + ":" + personId;

        TenantContext.runAs(homeAxis, () -> caches.getCache(PERMISSIONS).put(key, Set.of("org:read")));
        assertThat(cached(PERMISSIONS, homeAxis, key)).isNotNull();

        promotionCaches.evictAfterPlacementFlip(orgId);

        assertThat(cached(PERMISSIONS, homeAxis, key))
                .describedAs("the pooled home's prefix is not derivable from the promoted org, so only a"
                        + " clear() reaches this entry")
                .isNull();
    }

    /**
     * {@code org-entitlements} is the cache that CAN be evicted precisely, because it holds one entry
     * per tenant under {@link TenantCacheKeys#wholeTenant()} and its key helper refuses to write one
     * tenant's value under another's axis. So the flip takes the promoted tenant's entry and leaves
     * everyone else's — asserted here, because a {@code clear()} would pass the first half alone.
     */
    @Test
    void theFlipDropsThePromotedTenantsEntitlementsAndNobodyElses() {
        UUID promoted = UUID.randomUUID();
        UUID bystander = UUID.randomUUID();
        String key = TenantCacheKeys.wholeTenant();

        TenantContext.runAs(promoted, () -> caches.getCache(ENTITLEMENTS).put(key, Map.of("members.max", 5L)));
        TenantContext.runAs(bystander, () -> caches.getCache(ENTITLEMENTS).put(key, Map.of("members.max", 9L)));

        promotionCaches.evictAfterPlacementFlip(promoted);

        assertThat(cached(ENTITLEMENTS, promoted, key)).isNull();
        assertThat(cached(ENTITLEMENTS, bystander, key))
                .describedAs("promoting one tenant must not make every other tenant re-resolve its plan")
                .isNotNull();
    }

    /**
     * The deliberate non-eviction, asserted as an invariance rather than as a preference. ADR 0010 §6
     * originally listed {@code OrgResolutionCache} among the flip's steps; the entry it holds is
     * {@code organization.id}, read from two platform-tier tables, and the silo is NAMED after that id —
     * so the cached answer is not merely still present after a real promotion, it is still <em>correct</em>,
     * which is what the second assertion checks against the database rather than against the cache.
     */
    @Test
    void theResolutionCacheSurvivesBecauseAPromotionCannotChangeWhatItHolds() {
        String externalOrgId = "kc-" + UUID.randomUUID();
        UUID orgId = EdgeSeed.organization(jdbc, externalOrgId, "ext-" + UUID.randomUUID());
        String key = "KEYCLOAK|" + EdgeSeed.ISSUER + "|" + externalOrgId;
        caches.getCache(ORG_BY_EXTERNAL_ID).put(key, orgId.toString());

        silos.place(orgId); // the real thing: schema created, migrated, placement flipped to the silo
        promotionCaches.evictAfterPlacementFlip(orgId);

        Cache.ValueWrapper survivor = caches.getCache(ORG_BY_EXTERNAL_ID).get(key);
        assertThat(survivor).describedAs("a GLOBAL cache over platform-tier tables is untouched").isNotNull();

        UUID stillNamed = jdbc.queryForObject(
                "select organization_id from platform.external_organization where external_org_id = ?",
                UUID.class, externalOrgId);
        assertThat(survivor.get())
                .describedAs("and what it holds is still the RIGHT answer — the tenant moved, its id did"
                        + " not, and this cache holds the id")
                .isEqualTo(stillNamed.toString());
    }

    /**
     * {@code TenantSchemaFloor} is not a Spring cache, but it memoizes a decision derived from the very
     * registry row the flip rewrites, and its "at or above the floor" entry NEVER expires (that being
     * one-way is only true while a tenant stays in one schema). This drives the version backwards behind
     * the memo's back — the shape a promotion into a home this binary is too new for would produce — and
     * asserts the memo holds until the flip clears it.
     */
    @Test
    void theFlipMakesTheSchemaFloorAskTheRegistryAgain() {
        UUID orgId = organization();
        silos.place(orgId);

        assertThat(schemaFloor.admits(orgId))
                .describedAs("a freshly migrated silo is at head, so this settles as 'serve'")
                .isTrue();

        jdbc.update("update platform.tenant_placement set schema_version = '1' where org_id = ?", orgId);
        assertThat(schemaFloor.admits(orgId))
                .describedAs("a settled decision is remembered forever — that is the memo the flip breaks")
                .isTrue();

        promotionCaches.evictAfterPlacementFlip(orgId);

        assertThat(schemaFloor.admits(orgId))
                .describedAs("re-read: below MIN_TENANT_SCHEMA_VERSION, so this tenant now 503s")
                .isFalse();
    }

    // --------------------------------------------------------- AGENTS §5.5, now with a second schema

    /**
     * <b>AGENTS §5.5 inside a silo.</b> The invariant — a revoked membership denies the VERY NEXT
     * request — has always been asserted against tenants in {@code tenant_pool}, because until Phase 5
     * there was nowhere else to be. It has to hold identically for a promoted tenant, and the mechanism
     * it rests on is not obviously schema-independent: the removal's after-commit listener runs on a
     * pooled thread with no tenant axis, and reaches the entry through a {@code clear()} that names
     * neither key nor schema.
     *
     * <p>Removal is done the way {@code MemberService.remove} does it — the delete and the explicit
     * {@code MemberRemoved} in one transaction, pinned from outside it — because a repository delete
     * fires no {@code @DomainEvents} and the publish IS the eviction.
     */
    @Test
    void aRevokedMembershipDeniesTheVeryNextRequestInsideASilo() {
        UUID orgId = organization();
        String silo = silos.place(orgId); // place BEFORE the tenant-tier rows: this fixture copies nothing
        UUID personId = EdgeSeed.person(jdbc, "kc-" + UUID.randomUUID());
        UUID roleId = EdgeSeed.member(jdbc, orgId, personId, "ADMIN");
        grant(orgId, roleId, Permission.ORG_DELETE);

        assertThat(rowsIn(silo, orgId))
                .describedAs("the whole point of this test is the SECOND schema: with the org placed,"
                        + " its tenant-tier rows must land in %s. A zero here means the placement"
                        + " router is not routing a placed tenant to its silo yet, and everything"
                        + " below would be proving the pooled case under a siloed name", silo)
                .isOne();

        assertThat(authorization.permissions(personId, orgId)).containsExactly(Permission.ORG_DELETE.code());

        TenantContext.runAs(orgId, () -> transactions.executeWithoutResult(tx -> {
            jdbc.update("delete from membership where org_id = ? and person_id = ?", orgId, personId);
            events.publishEvent(new MemberRemoved(orgId, personId, Instant.now()));
        }));

        // The listener is after-commit and asynchronous; awaiting the eviction is awaiting the delivery,
        // not slack in the guarantee — the same shape OrgRbacAuthorityTest uses for the pooled case.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(authorization.permissions(personId, orgId)).isEmpty());
    }

    // ------------------------------------------------- why the organization stays in the permission key

    /**
     * <b>The test that fails if the organization is taken out of {@code org-permissions}' key.</b>
     *
     * <p>It reproduces the one production shape where the cache's tenant prefix does NOT name the
     * organization being asked about: a background sweep over the pooled home. ADR 0010 §3.4 fixes the
     * fan-out at one axis per HOME rather than one per tenant, {@code TenantHome.pool()} is that axis for
     * the shared schema — a uuid naming no organization — and {@code ExchangeScheduleFiringJob} runs
     * exactly this loop, asking per due schedule about whatever org the schedule belongs to, from inside
     * a transaction where {@code OrgAuthorizationImpl} deliberately does not re-pin.
     *
     * <p>So both answers below are filed under one prefix, and only the {@code #organizationId} half of
     * the key separates them. Remove it and the second read hits the first's entry: the person is granted
     * organization ALPHA's permissions while acting in BETA. That is a cross-tenant authorization bypass,
     * and it is why the "the schema is the isolation now, drop the prefix" argument is wrong for this
     * cache and stays wrong — the pooled home is many tenants under one axis and always will be.
     */
    @Test
    void theOrganizationInTheKeyIsWhatSeparatesTwoTenantsUnderOneHomesAxis() {
        EdgeSeed.MultiOrgPerson person = EdgeSeed.multiOrgPerson(jdbc, "ALPHA", "BETA");
        EdgeSeed.OrgSeat alpha = person.seats().get(0);
        EdgeSeed.OrgSeat beta = person.seats().get(1);
        grant(alpha.organizationId(), alpha.roleId(), Permission.ORG_DELETE);
        grant(beta.organizationId(), beta.roleId(), Permission.MEMBER_READ);

        TenantContext.runAs(TenantHome.pool().axis(), () -> transactions.executeWithoutResult(tx -> {
            assertThat(authorization.permissions(person.personId(), alpha.organizationId()))
                    .containsExactly(Permission.ORG_DELETE.code());
            assertThat(authorization.permissions(person.personId(), beta.organizationId()))
                    .describedAs("resolved under the SAME cache prefix as ALPHA's, one moment later —"
                            + " only the organization in the key keeps them apart")
                    .containsExactly(Permission.MEMBER_READ.code());
        }));
    }

    // ------------------------------------------------------------------------------------- fixtures

    private UUID organization() {
        return EdgeSeed.organization(jdbc, "kc-" + UUID.randomUUID(), "ext-" + UUID.randomUUID());
    }

    /**
     * Permissions onto a seeded role. Raw SQL under the org's own axis: {@code role_permission} is
     * tenant-tier and addressed bare, so it lands in whichever schema the org lives in — which is the
     * point when the org has already been placed in a silo.
     */
    private void grant(UUID orgId, UUID roleId, Permission... permissions) {
        TenantContext.runAs(orgId, () -> {
            for (Permission permission : permissions) {
                jdbc.update("insert into role_permission (role_id, permission) values (?, ?)",
                        roleId, permission.name());
            }
        });
    }

    /**
     * How many of this organization's memberships are in a NAMED schema. Qualified deliberately: the
     * question is about one schema rather than about the caller's axis, which is the one shape of
     * qualified tenant-table reference AGENTS §1 permits (and the reason {@code TenantSilos}' own guard
     * writes {@code tenant_pool.} the same way). The name goes through {@code requireSiloSchema}
     * because it is interpolated — a schema cannot be a bind parameter.
     */
    private int rowsIn(String siloSchema, UUID orgId) {
        Integer rows = jdbc.queryForObject(
                "select count(*) from " + TenantSchemas.requireSiloSchema(siloSchema)
                        + ".membership where org_id = ?", Integer.class, orgId);
        return rows == null ? 0 : rows;
    }

    /** One cache entry as the tenant whose prefix it was written under would see it. */
    private Object cached(String cacheName, UUID axis, Object key) {
        return TenantContext.callAs(axis, () -> {
            Cache.ValueWrapper wrapper = caches.getCache(cacheName).get(key);
            return wrapper == null ? null : wrapper.get();
        });
    }
}
