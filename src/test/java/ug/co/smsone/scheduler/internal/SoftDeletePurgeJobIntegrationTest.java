package ug.co.smsone.scheduler.internal;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Table;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.shared.persistence.MappedTables;
import ug.co.smsone.shared.persistence.SoftDeletableEntity;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;

/**
 * The purge is invisible to JPA by construction ({@code @SQLRestriction} hides every row it touches),
 * so the whole test speaks SQL: rows are seeded with an explicit {@code deleted_at}, which is also the
 * only way to age one past the retention window without waiting a month.
 *
 * <p>Runs against the shipped default window (P30D) rather than an overridden one — a wrong default is
 * exactly the kind of bug that would otherwise ship.
 *
 * <p><b>Which probes carry a pin, and why the others do not.</b> The harness pins PLATFORM on the test
 * thread, so a platform-tier seed ({@code organization}, {@code person}, {@code setting},
 * {@code feature_flag}, {@code user_device}) is already on its own axis and reads bare. A tenant-tier
 * one is not, and says so with {@code TenantContext.runAs(orgId, …)} — the same declaration the job
 * itself now makes for its tenant pass (ADR 0010 §2). The pin is on the seed rather than on the whole
 * test because getting it wrong is the bug under test: a probe row written into the wrong schema would
 * be invisible to the pass that is supposed to purge it, and the assertion would pass for the wrong
 * reason.
 */
class SoftDeletePurgeJobIntegrationTest extends AbstractIntegrationTest {

    /** Comfortably past the P30D default; "fresh" rows are deleted now and must survive. */
    private static final Instant AGED = Instant.now().minus(Duration.ofDays(60));
    private static final Instant FRESH = Instant.now();

    /**
     * The pooled tenant's axis, for the one check that is about a SCHEMA rather than about any org —
     * {@link #everyPurgeStepDeclaresTheTierItsTableActuallyLivesIn}. Same constant and same reasoning
     * as the job's own: a uuid in no {@code organization} row can only ever resolve to
     * {@code tenant_pool}. The probes below pin {@link #orgId} instead, because they DO have an org to
     * name and it is the one their rows belong to.
     */
    private static final UUID POOLED_TENANT = new UUID(0L, 0L);

    @Autowired
    private SoftDeletePurgeJob job;

    @Autowired
    private JdbcTemplate jdbc;

    /** The same source the job resolves table names from, so the probes address exactly what it does. */
    @Autowired
    private MappedTables tables;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private record Seeded(String table, UUID id) {
    }

    /** Insertion order; unwound in reverse so children go before the parents they reference. */
    private final List<Seeded> seeded = new ArrayList<>();

    /**
     * Trust grants are keyed on {@code (device_id, org_id)} and have no {@code id}, so {@link #track}
     * cannot hold them — and since V53 cut {@code user_device_trust_device_id_fkey} nothing removes
     * them with their device either, which is precisely what this class now tests.
     */
    private final List<UUID> seededTrustDevices = new ArrayList<>();

    /**
     * The tenant the probe rows hang off, and still a REAL {@code organization} row rather than a loose
     * uuid — though no longer because it has to be. {@code org_role.org_id} and {@code membership.org_id}
     * were foreign keys to it until V53 cut them for crossing the tenancy boundary (ADR 0010 §6), so
     * these probes would now insert happily against any uuid. It stays real deliberately: this class
     * exercises {@code heldGuard}, whose {@code legal_hold} anti-joins compare against exactly this
     * column, and a probe hanging off nothing would pass those guards for a reason the test never
     * intended. Seeded live, so the purge never treats it as a candidate; tracked first, so the reverse
     * unwind drops it last.
     */
    private UUID orgId;

    @BeforeEach
    void seedTenant() {
        UUID externalOrgId = UUID.randomUUID();
        orgId = track("organization",
                EdgeSeed.organization(jdbc, externalOrgId.toString(), "purge-tenant-" + suffix(externalOrgId)));
    }

    /**
     * ShedLock's advisor intercepts direct calls as well as cron ones, and {@code lockAtLeastFor} holds
     * the lock for 30s after a run — so every test after the first would be silently skipped and pass
     * for the wrong reason. Expiring the lock row (rather than deleting it) is what the provider's
     * acquire actually looks at; deleting it strands the provider's record cache on an UPDATE that
     * matches nothing.
     */
    private void runPurge() {
        // platform.shedlock, named: it has no entity, so it is qualified in its own SQL (ADR 0010 §2).
        jdbc.update("update platform.shedlock set lock_until = timestamp '1970-01-01 00:00:00' where name = ?",
                "soft-delete-purge");
        job.purgeExpiredSoftDeletes();
    }

    /**
     * Unwound on the TENANT axis, and the qualification is what lets one pin cover both tiers here:
     * {@link MappedTables} names a platform table {@code platform.<table>}, which resolves whatever the
     * {@code search_path} is, while a tenant table stays bare and is placed by the pin. That is a
     * property of the fixture, not of the job — the job takes two passes because Phase 7 splits the
     * DATABASES, and a teardown does not have to survive that.
     */
    @AfterEach
    void removeSurvivors() {
        if (orgId == null) {
            // Seeding never got as far as the tenant, so nothing was written and there is no axis to
            // pin. Returning keeps the real failure visible instead of burying it under this one.
            return;
        }
        TenantContext.runAs(orgId, () -> {
            for (UUID deviceId : seededTrustDevices) {
                jdbc.update("delete from user_device_trust where device_id = ?", deviceId);
            }
            for (int i = seeded.size() - 1; i >= 0; i--) {
                Seeded row = seeded.get(i);
                jdbc.update("delete from " + tables.qualified(row.table()) + " where id = ?", row.id());
            }
        });
    }

    @Test
    void purgesAgedRowsFromEverySoftDeletableTable() {
        UUID role = insertRole(AGED);
        UUID membership = insertMembership(role, AGED);
        UUID organization = insertOrganization(AGED);
        UUID person = insertPerson(AGED);
        UUID subscription = insertSubscription(AGED);
        UUID delivery = insertDelivery(subscription);
        UUID setting = insertSetting(AGED);
        UUID flag = insertFlag(AGED);

        runPurge();

        // Seven of the soft-deletable tables: one missing from the purge order leaks rows forever
        // (that the list is COMPLETE is what purgeOrderCoversEverySoftDeletableEntity proves).
        assertThat(exists("membership", membership)).isFalse();
        assertThat(exists("org_role", role)).isFalse();
        assertThat(exists("organization", organization)).isFalse();
        assertThat(exists("person", person)).isFalse();
        assertThat(exists("webhook_subscription", subscription)).isFalse();
        assertThat(exists("setting", setting)).isFalse();
        assertThat(exists("feature_flag", flag)).isFalse();

        // webhook_delivery has no purge step of its own; the FK cascade is what removes it.
        assertThat(exists("webhook_delivery", delivery)).isFalse();
    }

    /**
     * The case the purge order exists for: {@code membership.role_id} references {@code org_role(id)}
     * with no cascade, and the FK does not care that both rows are soft-deleted. Purging {@code org_role}
     * before {@code membership} raises a constraint violation, so this passing in a SINGLE run is the
     * assertion — a second run cleaning up the leftovers would not do.
     */
    @Test
    void purgesAnAgedMembershipAndItsAgedRoleInOneRun() {
        UUID role = insertRole(AGED);
        UUID membership = insertMembership(role, AGED);
        // TENANT-tier, and it must land in the same schema as the org_role it cascades from.
        TenantContext.runAs(orgId, () -> jdbc.update(
                "insert into role_permission (role_id, permission) values (?, 'member:read')", role));

        runPurge();

        assertThat(exists("membership", membership)).isFalse();
        assertThat(exists("org_role", role)).isFalse();
        // role_permission is an @ElementCollection with an on-delete-cascade FK: no purge step needed.
        assertThat(countPermissions(role)).isZero();
    }

    /**
     * The pathological FK the purge ORDER cannot help with: a LIVE membership pinning a role that has
     * aged out. Unlike the both-aged case above there is no ordering that resolves it, and the row does
     * not go away on its own — so an unguarded {@code delete from org_role} raises the same constraint
     * violation every night. {@code org_role} is THIRD in the order, which is what makes this
     * expensive: every table after it, {@code person} among them, would never purge again while
     * the retention window kept promising erasure.
     */
    @Test
    void aLiveMembershipPinningAnAgedRoleDoesNotStarveTheTablesBehindIt() {
        UUID pinnedRole = insertRole(AGED);
        UUID liveMembership = insertMembership(pinnedRole, null);
        UUID person = insertPerson(AGED);   // every one of these sits AFTER org_role in PURGE_ORDER
        UUID setting = insertSetting(AGED);
        UUID flag = insertFlag(AGED);

        runPurge();

        assertThat(exists("org_role", pinnedRole)).as("skipped, not deleted — the referrer is live").isTrue();
        assertThat(exists("membership", liveMembership)).isTrue();
        assertThat(exists("person", person)).isFalse();
        assertThat(exists("setting", setting)).isFalse();
        assertThat(exists("feature_flag", flag)).isFalse();
    }

    /**
     * A revoked device must leave no live trust grant — the guarantee
     * {@code user_device_trust_device_id_fkey}'s {@code ON DELETE CASCADE} used to make, and that V53
     * had to cut because the FK crossed the tenant boundary (ADR 0010 §6). Its replacement is three
     * mechanisms; this test covers the third, the RECONCILER, and it is deliberately driven the way the
     * mechanism it backstops cannot see.
     *
     * <p><b>The device here is soft-deleted by raw SQL and publishes no event</b>, because that is a
     * real path: {@code ComplianceService}'s erasure updates {@code user_device.deleted_at} directly.
     * The event listener never hears about it, so if the reconciler were missing, the grant would go on
     * satisfying {@code require_trusted_device} — and since V53 the enforcement query no longer joins
     * {@code user_device}, there is nothing left to notice that the device is dead.
     *
     * <p>And it keys on LIVENESS, not on existence: the revoked device is still well inside the P30D
     * retention window, so its own row is untouched by this run. A cascade that only fired on the hard
     * delete would leave the grant standing for thirty more days. The live device's grant in the same
     * run is the control — a sweep that took both would be a worse bug than the one it fixes.
     */
    @Test
    void aRevokedDeviceLosesItsTrustGrantsWhileItsOwnRowIsStillInsideRetention() {
        UUID revoked = insertDevice(FRESH);   // revoked seconds ago: nothing here purges the row itself
        UUID live = insertDevice(null);
        grantTrust(revoked);
        grantTrust(live);

        runPurge();

        assertThat(exists("user_device", revoked)).as("still inside retention — the row is the trail").isTrue();
        assertThat(trustGrants(revoked)).as("the grant a revoked device must not keep").isZero();
        assertThat(trustGrants(live)).as("the control: a live device keeps the trust its org granted").isEqualTo(1);
    }

    /**
     * {@code SoftDeletePurgeJob.CASCADES} is a second hand-written list, and it fails the same way the
     * first one does — silently: the rows it was meant to reconcile stay forever with nothing to say so.
     * Its keys name the {@link SoftDeletePurgeJob#PURGE_ORDER} table whose rows the child hangs off, and
     * pinning them to that list is what keeps a cascade from being declared against a table this job
     * does not manage at all.
     *
     * <p>The key is still the PARENT even though the sweep now runs in the pass its CHILD's tier names
     * — {@code user_device} is platform-tier and {@code user_device_trust} is the tenant's, which is why
     * the FK between them was cut in the first place. The parent is what the reconciler is ABOUT; the
     * tier is only where the statement has to run.
     */
    @Test
    void everyCascadeHangsOffATablePurgeOrderVisits() {
        assertThat(SoftDeletePurgeJob.cascadeParents())
                .isNotEmpty()
                .isSubsetOf(SoftDeletePurgeJob.purgeOrderTables());
    }

    /**
     * <b>The tier on each {@link SoftDeletePurgeJob.PurgeStep} decides which PASS visits the table, and
     * a wrong one fails at 04:00 rather than here — unless something checks it.</b> This is that
     * something, and it asks the DATABASE rather than a second hand-written list: for each step, does
     * the name the job will actually interpolate resolve on the axis the job will actually run it on?
     *
     * <p>{@code to_regclass} is the same question the purge's own SQL asks, on a connection routed the
     * same way, and it answers null instead of throwing — so a mis-tiered table shows up as a readable
     * assertion rather than as a swallowed nightly ERROR.
     *
     * <p>Each tier gets its own two-sided check, and the negative halves are the ones that catch a
     * mistake the positive half cannot:
     * <ul>
     *   <li>PLATFORM must be SCHEMA-QUALIFIED. Bare would resolve on the platform pass too — and then
     *       silently mean the tenant's copy the day anyone moved the pass.</li>
     *   <li>TENANT must be bare, resolve on the tenant axis, and NOT resolve on the platform one. A
     *       tenant-tier name that resolves under {@code platform} is a table that is really split, and
     *       its platform home would never be purged.</li>
     *   <li>BOTH must be bare and resolve on BOTH. A split table declared TENANT leaks its platform
     *       copy's tombstones forever — {@code document}'s platform copy is where PERSONAL documents
     *       live.</li>
     * </ul>
     */
    @Test
    void everyPurgeStepDeclaresTheTierItsTableActuallyLivesIn() {
        for (SoftDeletePurgeJob.PurgeStep step : SoftDeletePurgeJob.PURGE_ORDER) {
            String name = tables.qualified(step.table());
            boolean onPlatform = TenantContext.callAsPlatform(() -> resolves(name));
            boolean onTenant = TenantContext.callAs(POOLED_TENANT, () -> resolves(name));
            switch (step.tier()) {
                case PLATFORM -> {
                    assertThat(name).as("%s is PLATFORM, so the purge must NAME its schema", step.table())
                            .isEqualTo("platform." + step.table());
                    assertThat(onPlatform).as("%s must resolve on the platform pass", name).isTrue();
                }
                case TENANT -> {
                    assertThat(name).as("%s is TENANT, so search_path must place it", step.table())
                            .isEqualTo(step.table());
                    assertThat(onTenant).as("%s must resolve on the tenant pass", name).isTrue();
                    assertThat(onPlatform)
                            .as("%s resolves under `platform` too — it is a SPLIT table and the platform"
                                    + " copy would never be purged; declare it BOTH", name)
                            .isFalse();
                }
                case BOTH -> {
                    assertThat(name).as("%s is split, so both homes are reached by the same bare name",
                            step.table()).isEqualTo(step.table());
                    assertThat(onPlatform).as("%s must resolve on the platform pass", name).isTrue();
                    assertThat(onTenant).as("%s must resolve on the tenant pass", name).isTrue();
                }
            }
        }
    }

    /** Whether the current axis's {@code search_path} can reach {@code table}. */
    private boolean resolves(String table) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject("select to_regclass(?) is not null", Boolean.class, table));
    }

    /**
     * The purge order is a hand-written list, so the failure mode is omission: mapping an eighth
     * {@code SoftDeletableEntity} without adding its table here leaks deleted rows forever, and does it
     * silently — nothing errors, the rows simply never expire. Deriving the expected set from the
     * metamodel turns that into a build failure on the commit that introduces it.
     *
     * <p>It stays an EXACT match, both directions, and V53 is the reason to say so out loud: the
     * obvious way to make the job clear {@code user_device_trust} would have been to append it here,
     * and that would have broken this assertion for a true reason — the table is not soft-deletable, it
     * has no {@code deleted_at}, and {@code DELETE_BATCH} would have nothing to age it out by. It hangs
     * off {@code user_device} in {@code CASCADES} instead, which is why this list can go on meaning
     * exactly "every soft-deletable entity, and only those".
     */
    @Test
    void purgeOrderCoversEverySoftDeletableEntity() {
        List<String> mapped = entityManagerFactory.getMetamodel().getEntities().stream()
                .map(jakarta.persistence.metamodel.Type::getJavaType)
                .filter(SoftDeletableEntity.class::isAssignableFrom)
                .map(SoftDeletePurgeJobIntegrationTest::tableOf)
                .toList();

        assertThat(SoftDeletePurgeJob.purgeOrderTables())
                .containsExactlyInAnyOrderElementsOf(mapped)
                .doesNotHaveDuplicates();
    }

    private static String tableOf(Class<?> entity) {
        Table table = entity.getAnnotation(Table.class);
        // Every entity in this codebase names its table explicitly; an unnamed one would default to the
        // class name, which the purge SQL would then not find.
        assertThat(table).describedAs("%s must declare @Table(name = ...)", entity.getSimpleName())
                .isNotNull();
        return table.name();
    }

    @Test
    void keepsRowsInsideTheWindowAndRowsThatWereNeverDeleted() {
        UUID recentlyDeleted = insertSetting(FRESH);
        UUID live = insertSetting(null);
        UUID liveRole = insertRole(null);
        UUID liveMembership = insertMembership(liveRole, null);

        runPurge();

        assertThat(exists("setting", recentlyDeleted)).isTrue();
        assertThat(exists("setting", live)).isTrue();
        assertThat(exists("org_role", liveRole)).isTrue();
        assertThat(exists("membership", liveMembership)).isTrue();
    }

    private UUID insertRole(Instant deletedAt) {
        UUID id = UUID.randomUUID();
        // TENANT-tier (ADR 0010 §2), so the harness's platform pin cannot see it: on `platform, ext`
        // this insert fails with a relation that plainly exists, in the other schema.
        TenantContext.runAs(orgId, () -> jdbc.update("""
                insert into org_role (id, org_id, code, name, system_role, version, created_at, deleted_at)
                values (?, ?, ?, 'Purge probe', false, 0, now(), ?)
                """, id, orgId, "PURGE_" + suffix(id), timestamp(deletedAt)));
        return track("org_role", id);
    }

    /**
     * {@code person_id}, a uuid — the member is a person now, not a Keycloak subject string. It is a
     * soft ref (no FK), so a probe membership does not need a person row behind it; {@code org_id} and
     * {@code role_id} are the two that are enforced.
     */
    private UUID insertMembership(UUID roleId, Instant deletedAt) {
        UUID id = UUID.randomUUID();
        // TENANT-tier, like the org_role it points at — the FK between them is intra-tier, which is
        // why V53 could keep it and cut the one to `organization`.
        TenantContext.runAs(orgId, () -> jdbc.update("""
                insert into membership (id, org_id, person_id, role_id, status, version, created_at, deleted_at)
                values (?, ?, ?, ?, 'ACTIVE', 0, now(), ?)
                """, id, orgId, UUID.randomUUID(), roleId, timestamp(deletedAt)));
        return track("membership", id);
    }

    /**
     * No {@code kc_org_id}: the provider's id moved to {@code external_organization} and this row mints
     * its own key. Deliberately WITHOUT that link — an aged tenant with no provider row still has to
     * purge, and the linked case is the tenant seeded in {@link #seedTenant()}.
     */
    private UUID insertOrganization(Instant deletedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into organization (id, alias, name, status, version, created_at, deleted_at)
                values (?, ?, 'Purge probe', 'ACTIVE', 0, now(), ?)
                """, id, "purge-" + suffix(id), timestamp(deletedAt));
        return track("organization", id);
    }

    /**
     * The identity row itself. Seeded bare rather than through {@code EdgeSeed}: the probe needs a
     * {@code deleted_at} no live seed writes, and the subject/e-mail that used to sit on this row are
     * now separate tables the purge reaches on their own. {@code invited_at} (V10's rename) plus
     * {@code activated_at}, because ACTIVE with no activation instant is a state nothing produces.
     */
    private UUID insertPerson(Instant deletedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into person (id, status, invited_at, activated_at, version, created_at, deleted_at)
                values (?, 'ACTIVE', now(), now(), 0, now(), ?)
                """, id, timestamp(deletedAt));
        return track("person", id);
    }

    private UUID insertSubscription(Instant deletedAt) {
        UUID id = UUID.randomUUID();
        // TENANT-tier: a subscription is the tenant's endpoint and travels with it.
        TenantContext.runAs(orgId, () -> jdbc.update("""
                insert into webhook_subscription
                    (id, org_id, url, secret, event_types, status, version, created_at, deleted_at)
                values (?, ?, 'https://example.test/hook', 'secret', 'settings.changed', 'ACTIVE', 0, now(), ?)
                """, id, orgId, timestamp(deletedAt)));
        return track("webhook_subscription", id);
    }

    private UUID insertDelivery(UUID subscriptionId) {
        UUID id = UUID.randomUUID();
        // TENANT-tier, and it has to land in the same schema as the subscription it cascades from.
        TenantContext.runAs(orgId, () -> jdbc.update("""
                insert into webhook_delivery
                    (id, subscription_id, org_id, event_type, payload, status, attempts, max_attempts,
                     next_attempt_at, created_at)
                values (?, ?, ?, 'settings.changed', '{}', 'DELIVERED', 1, 5, now(), now())
                """, id, subscriptionId, orgId));
        return track("webhook_delivery", id);
    }

    private UUID insertSetting(Instant deletedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into setting (id, setting_key, setting_value, version, created_at, deleted_at)
                values (?, ?, 'x', 0, now(), ?)
                """, id, "purge.probe." + suffix(id), timestamp(deletedAt));
        return track("setting", id);
    }

    private UUID insertFlag(Instant deletedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into feature_flag (id, flag_key, enabled, version, created_at, deleted_at)
                values (?, ?, false, 0, now(), ?)
                """, id, "purge.probe." + suffix(id), timestamp(deletedAt));
        return track("feature_flag", id);
    }

    /** A person's device. {@code person_id} is a soft ref (V31), so no person row is needed behind it. */
    private UUID insertDevice(Instant deletedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into user_device (id, person_id, name, kind, fingerprint, version, created_at, deleted_at)
                values (?, ?, 'Purge probe', 'BROWSER', ?, 0, now(), ?)
                """, id, UUID.randomUUID(), "purge-fp-" + suffix(id), timestamp(deletedAt));
        return track("user_device", id);
    }

    /**
     * The tenant's grant over that device. {@code person_id} and {@code fingerprint} are the V53
     * denormalization, copied from the device row — read back rather than passed in, so the probe row
     * carries the same values the application would have written.
     */
    private void grantTrust(UUID deviceId) {
        // BOTH tiers in one statement, which is why the pin and the qualification are both here: the
        // grant is the TENANT's row, the device it copies from is PLATFORM's. That is the shape the FK
        // used to have and the reason V53 had to cut it (ADR 0010 §6) — and it is what the reconciler
        // under test reconciles.
        TenantContext.runAs(orgId, () -> jdbc.update("""
                insert into user_device_trust (device_id, org_id, granted_at, person_id, fingerprint)
                select ?, ?, now(), d.person_id, d.fingerprint from platform.user_device d where d.id = ?
                """, deviceId, orgId, deviceId));
        seededTrustDevices.add(deviceId);
    }

    private int trustGrants(UUID deviceId) {
        Integer count = TenantContext.callAs(orgId, () -> jdbc.queryForObject(
                "select count(*) from user_device_trust where device_id = ?", Integer.class, deviceId));
        return count == null ? 0 : count;
    }

    private UUID track(String table, UUID id) {
        seeded.add(new Seeded(table, id));
        return id;
    }

    /**
     * Reads across both tiers on ONE axis, which the assertions need and the job deliberately does not
     * do: the name comes from {@link MappedTables}, so a platform table arrives already qualified and
     * resolves whatever the pin is, while a tenant one stays bare and is placed by it.
     */
    private boolean exists(String table, UUID id) {
        Integer count = TenantContext.callAs(orgId, () -> jdbc.queryForObject(
                "select count(*) from " + tables.qualified(table) + " where id = ?", Integer.class, id));
        return count != null && count > 0;
    }

    /** {@code role_permission} is TENANT-tier and has no entity of its own — it cascades from org_role. */
    private int countPermissions(UUID roleId) {
        Integer count = TenantContext.callAs(orgId, () -> jdbc.queryForObject(
                "select count(*) from role_permission where role_id = ?", Integer.class, roleId));
        return count == null ? 0 : count;
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    /** One Postgres container serves every test class, so seeded keys must not collide with theirs. */
    private static String suffix(UUID id) {
        return id.toString().substring(0, 8);
    }
}
