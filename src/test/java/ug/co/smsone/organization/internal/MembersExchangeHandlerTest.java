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
import ug.co.smsone.identity.PersonProvisioning;
import ug.co.smsone.identity.ProvisionRequest;
import ug.co.smsone.identity.ProvisionedPerson;
import ug.co.smsone.identity.ProviderOrgMembership;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;

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
    private PersonProvisioning provisioning;

    @MockitoBean
    private ProviderOrgMembership providerOrgMembership;

    @MockitoBean
    private KeycloakOrgAdminGateway keycloakOrg;

    @Test
    void importInvitesThroughTheEscalationGuardOfTheRequester() {
        UUID orgId = seedOrg();
        UUID requester = UUID.randomUUID();
        seedRole(orgId, "INVITER", "MEMBER_INVITE", "MEMBER_READ", "ORG_READ");
        seedRole(orgId, "SWEEPER", "MEMBER_INVITE", "MEMBER_REMOVE");
        seedMembership(orgId, requester, "INVITER");
        // One person per address, minted here so the test can assert on the id the invite produced.
        Map<String, UUID> provisioned = new HashMap<>();
        given(provisioning.provision(any())).willAnswer(invocation -> {
            ProvisionRequest request = invocation.getArgument(0);
            UUID personId = provisioned.computeIfAbsent(request.email(), email -> UUID.randomUUID());
            return new ProvisionedPerson(personId, request.email(), false);
        });
        ExchangeContext context = new ExchangeContext(orgId, requester);

        assertThat(importRecord(context, record("new1@x.com", "INVITER")))
                .isEqualTo(ImportOutcome.APPLIED);
        assertThat(members(orgId, provisioned.get("new1@x.com"))).isEqualTo(1);

        // Replay of the same record (a resumed batch) is absorbed, not duplicated.
        assertThat(importRecord(context, record("new1@x.com", "INVITER")))
                .isEqualTo(ImportOutcome.APPLIED);
        assertThat(members(orgId, provisioned.get("new1@x.com"))).isEqualTo(1);

        // SWEEPER carries member:remove, which the requester does not hold — a grant they cannot make.
        assertThatThrownBy(() -> importRecord(context, record("new2@x.com", "SWEEPER")))
                .isInstanceOf(InvalidRecordException.class)
                .hasMessageContaining("member:remove");
        assertThat(provisioned).doesNotContainKey("new2@x.com");

        assertThatThrownBy(() -> importRecord(context, record("", "INVITER")))
                .isInstanceOf(InvalidRecordException.class)
                .hasMessage("email is required.");
        assertThatThrownBy(() -> importRecord(context, record("not-an-address", "INVITER")))
                .isInstanceOf(InvalidRecordException.class)
                .hasMessageContaining("not a valid address");
        assertThatThrownBy(() -> importRecord(context, record("new3@x.com", "GHOST")))
                .isInstanceOf(InvalidRecordException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void exportEmitsAReImportableRosterWithDirectoryEmails() {
        UUID orgId = seedOrg();
        seedRole(orgId, "INVITER", "MEMBER_INVITE", "MEMBER_READ", "ORG_READ");
        UUID reachable = EdgeSeed.personWithEmail(jdbc, "kc-" + UUID.randomUUID(), "a@x.com");
        UUID unreachable = UUID.randomUUID();
        seedMembership(orgId, reachable, "INVITER");
        seedMembership(orgId, unreachable, "INVITER");

        List<Map<String, String>> rows = new ArrayList<>();
        ExchangeContext context = new ExchangeContext(orgId, UUID.randomUUID());
        TenantContext.runAs(orgId, () -> handler.export(context, rows::add));

        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> assertThat(row.keySet())
                .containsExactlyInAnyOrderElementsOf(handler.header()));
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.get("email")).isEqualTo("a@x.com");
            assertThat(row.get("roleCode")).isEqualTo("INVITER");
        });
        // The second member has no contact on file: exported with a blank email, but still a valid row.
        assertThat(rows).anySatisfy(row -> assertThat(row.get("email")).isEmpty());
    }

    /**
     * The tenant axis {@code ExchangeWorker} declares before it hands a record to a handler
     * ({@code TenantContext.callAs(job.orgId(), …)}), spelled out here because this test drives the
     * handler directly, off any job. ONE span, not two: the roster it writes — {@code membership},
     * {@code org_role}, {@code role_permission} — is tenant-tier (ADR 0010 §2), and the platform-tier
     * rows the same call reaches ({@code person}, {@code person_contact},
     * {@code platform.org_membership_index}) are addressed by name, so they resolve from here too.
     */
    private ImportOutcome importRecord(ExchangeContext context, Map<String, String> record) {
        return TenantContext.callAs(context.orgId(), () -> handler.importRecord(context, record));
    }

    private static Map<String, String> record(String email, String roleCode) {
        Map<String, String> record = new HashMap<>();
        record.put("email", email);
        record.put("givenName", "First");
        record.put("familyName", "Last");
        record.put("roleCode", roleCode);
        return record;
    }

    private int members(UUID orgId, UUID personId) {
        Integer n = TenantContext.callAs(orgId, () -> jdbc.queryForObject(
                "select count(*) from membership where org_id = ? and person_id = ? and deleted_at is null",
                Integer.class, orgId, personId));
        return n == null ? 0 : n;
    }

    /** A tenant with the provider link the invite path resolves before it provisions anybody. */
    private UUID seedOrg() {
        return EdgeSeed.organization(jdbc, "kc-org-" + UUID.randomUUID(), "acme-" + UUID.randomUUID());
    }

    /**
     * {@code org_role} and {@code role_permission} are TENANT-tier (ADR 0010 §2), so the seed runs on
     * the organization's own axis — the harness pins PLATFORM, where neither table exists.
     */
    private void seedRole(UUID orgId, String code, String... permissions) {
        UUID roleId = UUID.randomUUID();
        TenantContext.runAs(orgId, () -> {
            jdbc.update("insert into org_role (id, org_id, code, name, system_role, version, created_at) "
                    + "values (?, ?, ?, ?, false, 0, now())", roleId, orgId, code, code);
            for (String permission : permissions) {
                jdbc.update("insert into role_permission (role_id, permission) values (?, ?)", roleId, permission);
            }
        });
    }

    private void seedMembership(UUID orgId, UUID personId, String roleCode) {
        TenantContext.runAs(orgId, () -> {
            UUID roleId = jdbc.queryForObject(
                    "select id from org_role where org_id = ? and code = ?", UUID.class, orgId, roleCode);
            jdbc.update("insert into membership (id, org_id, person_id, role_id, status, version, created_at) "
                    + "values (?, ?, ?, ?, 'ACTIVE', 0, now())", UUID.randomUUID(), orgId, personId, roleId);
            // Qualified from inside the tenant's axis, exactly as OrgProjectionWriter writes it in the
            // same transaction as the membership row (ADR 0010 §2.1).
            jdbc.update("insert into platform.org_membership_index (person_id, org_id, status) "
                    + "values (?, ?, 'ACTIVE') on conflict (person_id, org_id) do nothing",
                    personId, orgId);
        });
    }
}
