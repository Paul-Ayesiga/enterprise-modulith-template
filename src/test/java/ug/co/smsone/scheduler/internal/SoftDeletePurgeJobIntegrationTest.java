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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.shared.persistence.SoftDeletableEntity;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The purge is invisible to JPA by construction ({@code @SQLRestriction} hides every row it touches),
 * so the whole test speaks SQL: rows are seeded with an explicit {@code deleted_at}, which is also the
 * only way to age one past the retention window without waiting a month.
 *
 * <p>Runs against the shipped default window (P30D) rather than an overridden one — a wrong default is
 * exactly the kind of bug that would otherwise ship.
 */
class SoftDeletePurgeJobIntegrationTest extends AbstractIntegrationTest {

    /** Comfortably past the P30D default; "fresh" rows are deleted now and must survive. */
    private static final Instant AGED = Instant.now().minus(Duration.ofDays(60));
    private static final Instant FRESH = Instant.now();

    @Autowired
    private SoftDeletePurgeJob job;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private record Seeded(String table, UUID id) {
    }

    /** Insertion order; unwound in reverse so children go before the parents they reference. */
    private final List<Seeded> seeded = new ArrayList<>();

    private final UUID orgId = UUID.randomUUID();

    /**
     * ShedLock's advisor intercepts direct calls as well as cron ones, and {@code lockAtLeastFor} holds
     * the lock for 30s after a run — so every test after the first would be silently skipped and pass
     * for the wrong reason. Expiring the lock row (rather than deleting it) is what the provider's
     * acquire actually looks at; deleting it strands the provider's record cache on an UPDATE that
     * matches nothing.
     */
    private void runPurge() {
        jdbc.update("update shedlock set lock_until = timestamp '1970-01-01 00:00:00' where name = ?",
                "soft-delete-purge");
        job.purgeExpiredSoftDeletes();
    }

    @AfterEach
    void removeSurvivors() {
        for (int i = seeded.size() - 1; i >= 0; i--) {
            Seeded row = seeded.get(i);
            jdbc.update("delete from " + row.table() + " where id = ?", row.id());
        }
    }

    @Test
    void purgesAgedRowsFromEverySoftDeletableTable() {
        UUID role = insertRole(AGED);
        UUID membership = insertMembership(role, AGED);
        UUID organization = insertOrganization(AGED);
        UUID user = insertUser(AGED);
        UUID subscription = insertSubscription(AGED);
        UUID delivery = insertDelivery(subscription);
        UUID setting = insertSetting(AGED);
        UUID flag = insertFlag(AGED);

        runPurge();

        // All seven soft-deletable tables: a table missing from the purge order leaks rows forever.
        assertThat(exists("membership", membership)).isFalse();
        assertThat(exists("org_role", role)).isFalse();
        assertThat(exists("organization", organization)).isFalse();
        assertThat(exists("app_user", user)).isFalse();
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
        jdbc.update("insert into role_permission (role_id, permission) values (?, 'member:read')", role);

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
     * violation every night. {@code org_role} is SECOND in the order, which is what makes this
     * expensive: the four tables after it, {@code app_user} among them, would never purge again while
     * the retention window kept promising erasure.
     */
    @Test
    void aLiveMembershipPinningAnAgedRoleDoesNotStarveTheTablesBehindIt() {
        UUID pinnedRole = insertRole(AGED);
        UUID liveMembership = insertMembership(pinnedRole, null);
        UUID user = insertUser(AGED);       // every one of these sits AFTER org_role in PURGE_ORDER
        UUID setting = insertSetting(AGED);
        UUID flag = insertFlag(AGED);

        runPurge();

        assertThat(exists("org_role", pinnedRole)).as("skipped, not deleted — the referrer is live").isTrue();
        assertThat(exists("membership", liveMembership)).isTrue();
        assertThat(exists("app_user", user)).isFalse();
        assertThat(exists("setting", setting)).isFalse();
        assertThat(exists("feature_flag", flag)).isFalse();
    }

    /**
     * The purge order is a hand-written list, so the failure mode is omission: mapping an eighth
     * {@code SoftDeletableEntity} without adding its table here leaks deleted rows forever, and does it
     * silently — nothing errors, the rows simply never expire. Deriving the expected set from the
     * metamodel turns that into a build failure on the commit that introduces it.
     */
    @Test
    void purgeOrderCoversEverySoftDeletableEntity() {
        List<String> mapped = entityManagerFactory.getMetamodel().getEntities().stream()
                .map(jakarta.persistence.metamodel.Type::getJavaType)
                .filter(SoftDeletableEntity.class::isAssignableFrom)
                .map(SoftDeletePurgeJobIntegrationTest::tableOf)
                .toList();

        assertThat(SoftDeletePurgeJob.PURGE_ORDER)
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
        jdbc.update("""
                insert into org_role (id, org_id, code, name, system_role, version, created_at, deleted_at)
                values (?, ?, ?, 'Purge probe', false, 0, now(), ?)
                """, id, orgId, "PURGE_" + suffix(id), timestamp(deletedAt));
        return track("org_role", id);
    }

    private UUID insertMembership(UUID roleId, Instant deletedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into membership (id, org_id, user_subject, role_id, status, version, created_at, deleted_at)
                values (?, ?, ?, ?, 'ACTIVE', 0, now(), ?)
                """, id, orgId, UUID.randomUUID().toString(), roleId, timestamp(deletedAt));
        return track("membership", id);
    }

    private UUID insertOrganization(Instant deletedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into organization (id, kc_org_id, alias, name, status, version, created_at, deleted_at)
                values (?, ?, ?, 'Purge probe', 'ACTIVE', 0, now(), ?)
                """, id, UUID.randomUUID(), "purge-" + suffix(id), timestamp(deletedAt));
        return track("organization", id);
    }

    private UUID insertUser(Instant deletedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into app_user (id, subject, email, status, provisioned_at, version, created_at, deleted_at)
                values (?, ?, ?, 'ACTIVE', now(), 0, now(), ?)
                """, id, UUID.randomUUID().toString(), "purge-" + suffix(id) + "@example.test",
                timestamp(deletedAt));
        return track("app_user", id);
    }

    private UUID insertSubscription(Instant deletedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into webhook_subscription
                    (id, org_id, url, secret, event_types, status, version, created_at, deleted_at)
                values (?, ?, 'https://example.test/hook', 'secret', 'settings.changed', 'ACTIVE', 0, now(), ?)
                """, id, orgId, timestamp(deletedAt));
        return track("webhook_subscription", id);
    }

    private UUID insertDelivery(UUID subscriptionId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into webhook_delivery
                    (id, subscription_id, org_id, event_type, payload, status, attempts, max_attempts,
                     next_attempt_at, created_at)
                values (?, ?, ?, 'settings.changed', '{}', 'DELIVERED', 1, 5, now(), now())
                """, id, subscriptionId, orgId);
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

    private UUID track(String table, UUID id) {
        seeded.add(new Seeded(table, id));
        return id;
    }

    private boolean exists(String table, UUID id) {
        Integer count = jdbc.queryForObject(
                "select count(*) from " + table + " where id = ?", Integer.class, id);
        return count != null && count > 0;
    }

    private int countPermissions(UUID roleId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from role_permission where role_id = ?", Integer.class, roleId);
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
