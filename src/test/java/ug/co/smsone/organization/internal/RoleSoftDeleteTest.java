package ug.co.smsone.organization.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.organization.Permission;
import ug.co.smsone.shared.persistence.SoftDeleteRecovery;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * A role is the one soft-deletable aggregate whose payload IS the authorization data, and it is also
 * the only one with an {@code @ElementCollection}. Those two facts collide: {@code @SQLDelete}
 * overrides only the entity row's own delete statement, so Hibernate still schedules the collection
 * remove and {@code repository.delete(role)} would stamp {@code deleted_at} while hard-deleting
 * {@code role_permission} underneath it. Nothing surfaces that at delete time — it surfaces when
 * someone restores the role and it grants nothing.
 */
class RoleSoftDeleteTest extends AbstractIntegrationTest {

    private static final Set<Permission> GRANTED = Set.of(Permission.MEMBER_READ, Permission.ORG_READ);

    @Autowired
    private RoleService roleService;

    @Autowired
    private RoleRepository roles;

    @Autowired
    private SoftDeleteRecovery recovery;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void deletingARoleHidesItWithoutTouchingItsPermissions() {
        UUID orgId = UUID.randomUUID();
        UUID id = roles.save(Role.create(orgId, "AUDITOR", "Auditor", false, null, GRANTED)).getId();

        roleService.delete(orgId, id);

        assertThat(roles.findById(id)).as("hidden from every ordinary read path").isEmpty();
        assertThat(deletedAt(id)).isNotNull();
        assertThat(permissionCount(id)).as("nothing is scrubbed, only stamped").isEqualTo(GRANTED.size());
    }

    /**
     * The consequence, and the reason the assertion above is not enough on its own: a restore that
     * returns an empty permission bundle reports success and locks out every member reassigned to the
     * role, with no error anywhere. The original set survives only in the audit row otherwise.
     */
    @Test
    void restoringADeletedRoleReturnsItsOriginalPermissions() {
        UUID orgId = UUID.randomUUID();
        UUID id = roles.save(Role.create(orgId, "AUDITOR", "Auditor", false, null, GRANTED)).getId();
        roleService.delete(orgId, id);

        recovery.restore(recovery.findDeleted(Role.class, id).orElseThrow());

        assertThat(roles.findById(id).orElseThrow().getPermissions())
                .containsExactlyInAnyOrderElementsOf(GRANTED);
    }

    private Timestamp deletedAt(UUID id) {
        return jdbc.queryForObject("select deleted_at from org_role where id = ?", Timestamp.class, id);
    }

    private int permissionCount(UUID roleId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from role_permission where role_id = ?", Integer.class, roleId);
        return count == null ? 0 : count;
    }
}
