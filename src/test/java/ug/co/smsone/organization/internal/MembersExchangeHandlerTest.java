package ug.co.smsone.organization.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ug.co.smsone.exchange.ExchangeContext;
import ug.co.smsone.exchange.ImportOutcome;
import ug.co.smsone.exchange.InvalidRecordException;
import ug.co.smsone.identity.ProvisionRequest;
import ug.co.smsone.identity.ProvisionedUser;
import ug.co.smsone.identity.UserProvisioning;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The reference handler's business rules: imports run the SAME invite pipeline as the REST
 * surface — including the escalation guard, evaluated against the REQUESTER, not a caller — data
 * problems surface as curated {@link InvalidRecordException}s, replays are idempotent, and the
 * export emits a re-importable roster with emails resolved through the identity directory.
 */
class MembersExchangeHandlerTest extends AbstractIntegrationTest {

    @Autowired
    private MembersExchangeHandler handler;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private UserProvisioning provisioning;

    @MockitoBean
    private KeycloakOrgAdminGateway keycloakOrg;

    @Test
    void importInvitesThroughTheEscalationGuardOfTheRequester() {
        UUID orgId = UUID.randomUUID();
        seedOrg(orgId);
        seedRole(orgId, "INVITER", "MEMBER_INVITE", "MEMBER_READ", "ORG_READ");
        seedRole(orgId, "SWEEPER", "MEMBER_INVITE", "MEMBER_REMOVE");
        seedMembership(orgId, "requester-1", "INVITER");
        given(provisioning.provision(any())).willAnswer(invocation -> {
            ProvisionRequest request = invocation.getArgument(0);
            return new ProvisionedUser("sub-" + request.email(), request.email(), false);
        });
        ExchangeContext context = new ExchangeContext(orgId, "requester-1");

        assertThat(handler.importRecord(context, record("new1@x.com", "INVITER")))
                .isEqualTo(ImportOutcome.APPLIED);
        assertThat(members(orgId, "sub-new1@x.com")).isEqualTo(1);

        // Replay of the same record (a resumed batch) is absorbed, not duplicated.
        assertThat(handler.importRecord(context, record("new1@x.com", "INVITER")))
                .isEqualTo(ImportOutcome.APPLIED);
        assertThat(members(orgId, "sub-new1@x.com")).isEqualTo(1);

        // SWEEPER carries member:remove, which requester-1 does not hold — a grant they cannot make.
        assertThatThrownBy(() -> handler.importRecord(context, record("new2@x.com", "SWEEPER")))
                .isInstanceOf(InvalidRecordException.class)
                .hasMessageContaining("member:remove");
        assertThat(members(orgId, "sub-new2@x.com")).isZero();

        assertThatThrownBy(() -> handler.importRecord(context, record("", "INVITER")))
                .isInstanceOf(InvalidRecordException.class)
                .hasMessage("email is required.");
        assertThatThrownBy(() -> handler.importRecord(context, record("not-an-address", "INVITER")))
                .isInstanceOf(InvalidRecordException.class)
                .hasMessageContaining("not a valid address");
        assertThatThrownBy(() -> handler.importRecord(context, record("new3@x.com", "GHOST")))
                .isInstanceOf(InvalidRecordException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void exportEmitsAReImportableRosterWithDirectoryEmails() {
        UUID orgId = UUID.randomUUID();
        seedOrg(orgId);
        seedRole(orgId, "INVITER", "MEMBER_INVITE", "MEMBER_READ", "ORG_READ");
        seedMembership(orgId, "sub-a", "INVITER");
        seedMembership(orgId, "sub-b", "INVITER");
        jdbc.update("insert into app_user (id, subject, email, status, provisioned_at, version, created_at) "
                + "values (?, 'sub-a', 'a@x.com', 'ACTIVE', now(), 0, now())", UUID.randomUUID());

        List<Map<String, String>> rows = new ArrayList<>();
        handler.export(new ExchangeContext(orgId, "requester-1"), rows::add);

        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> assertThat(row.keySet())
                .containsExactlyInAnyOrderElementsOf(handler.header()));
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.get("email")).isEqualTo("a@x.com");
            assertThat(row.get("roleCode")).isEqualTo("INVITER");
        });
        // sub-b has no directory projection: exported with a blank email, but still a valid row.
        assertThat(rows).anySatisfy(row -> assertThat(row.get("email")).isEmpty());
    }

    private static Map<String, String> record(String email, String roleCode) {
        Map<String, String> record = new HashMap<>();
        record.put("email", email);
        record.put("firstName", "First");
        record.put("lastName", "Last");
        record.put("roleCode", roleCode);
        return record;
    }

    private int members(UUID orgId, String subject) {
        Integer n = jdbc.queryForObject(
                "select count(*) from membership where org_id = ? and user_subject = ? and deleted_at is null",
                Integer.class, orgId, subject);
        return n == null ? 0 : n;
    }

    private void seedOrg(UUID orgId) {
        jdbc.update("insert into organization (id, kc_org_id, alias, name, status, version, created_at) "
                        + "values (?, ?, ?, ?, 'ACTIVE', 0, now()) "
                        + "on conflict (kc_org_id) where deleted_at is null do nothing",
                UUID.randomUUID(), orgId, "org-" + orgId.toString().substring(0, 13), "Org " + orgId);
    }

    private void seedRole(UUID orgId, String code, String... permissions) {
        UUID roleId = UUID.randomUUID();
        jdbc.update("insert into org_role (id, org_id, code, name, system_role, version, created_at) "
                + "values (?, ?, ?, ?, false, 0, now())", roleId, orgId, code, code);
        for (String permission : permissions) {
            jdbc.update("insert into role_permission (role_id, permission) values (?, ?)", roleId, permission);
        }
    }

    private void seedMembership(UUID orgId, String subject, String roleCode) {
        UUID roleId = jdbc.queryForObject(
                "select id from org_role where org_id = ? and code = ?", UUID.class, orgId, roleCode);
        jdbc.update("insert into membership (id, org_id, user_subject, role_id, status, version, created_at) "
                + "values (?, ?, ?, ?, 'ACTIVE', 0, now())", UUID.randomUUID(), orgId, subject, roleId);
    }
}
