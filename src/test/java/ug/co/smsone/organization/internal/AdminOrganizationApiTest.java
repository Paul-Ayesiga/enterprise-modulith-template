package ug.co.smsone.organization.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The platform tenant surface and the lifecycle's terminal step: support reads the fleet, one
 * tenant and its roster; delete refuses a live tenant (409), soft-deletes a SUSPENDED one, leaves
 * the audit row, and the deleted org vanishes from the surface (404).
 */
@AutoConfigureMockMvc
class AdminOrganizationApiTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void supportReadsTheFleetAndAdminDeletesOnlyFromSuspended() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        seedOrgWithMember(orgId, member);

        mockMvc.perform(get("/api/v1/admin/orgs").param("page[size]", "5").with(support()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
        mockMvc.perform(get("/api/v1/admin/orgs/{id}", orgId).with(support()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.status").value("ACTIVE"));
        mockMvc.perform(get("/api/v1/admin/orgs/{id}/members", orgId).with(support()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].attributes.personId").value(member.toString()))
                .andExpect(jsonPath("$.data[0].attributes.roleCode").value("FLEET"));

        // Live tenants refuse deletion; the status filter narrows the fleet view.
        mockMvc.perform(delete("/api/v1/admin/orgs/{id}", orgId).with(admin()))
                .andExpect(status().isConflict());
        jdbc.update("update organization set status = 'SUSPENDED' where id = ?", orgId);
        mockMvc.perform(get("/api/v1/admin/orgs").param("status", "suspended").with(support()))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/admin/orgs/{id}", orgId).with(admin()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/admin/orgs/{id}", orgId).with(support()))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject(
                "select count(*) from organization where id = ? and deleted_at is not null",
                Integer.class, orgId)).as("soft, restorable until the purge").isEqualTo(1);
        // On the ORG's axis: audit_log is a split table routed on org_id (ADR 0010 §2), and this row
        // carries one — so AuditLogImpl wrote it to the tenant's copy, and the same unqualified name
        // on the harness's platform pin would read the platform copy and report zero.
        assertThat(TenantContext.callAs(orgId, () -> jdbc.queryForObject(
                "select count(*) from audit_log where action = 'organization.deleted' and org_id = ?",
                Integer.class, orgId))).isEqualTo(1);
    }

    @Test
    void theSurfaceIsPlatformTiered() throws Exception {
        mockMvc.perform(get("/api/v1/admin/orgs")
                        .with(jwt().jwt(t -> t.subject("nobody"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/admin/orgs/{id}", UUID.randomUUID()).with(support()))
                .andExpect(status().isForbidden());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor support() {
        return jwt().jwt(t -> t.subject("support-1"))
                .authorities(new SimpleGrantedAuthority("ROLE_platform-support"));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return jwt().jwt(t -> t.subject("admin-1"))
                .authorities(new SimpleGrantedAuthority("ROLE_platform-admin"));
    }

    /**
     * organization.id IS the tenant key now — the test supplies it, nothing mints a provider id.
     *
     * <p><b>The fixture straddles both tiers and says which is which</b> (ADR 0010 §2).
     * {@code organization} is platform-tier and stays on the harness's PLATFORM pin — it is the
     * routing registry, and the row has to exist before there is a tenant to route to. The roster —
     * {@code org_role}, {@code role_permission}, {@code membership} — is tenant-tier and lives in a
     * schema that pin cannot see, so it takes one span on the organization's own axis. That split is
     * the same one {@code AdminOrganizationController.listMembers} makes at runtime, which is exactly
     * what the roster assertion above is reading back.
     */
    private void seedOrgWithMember(UUID orgId, UUID personId) {
        jdbc.update("insert into organization (id, alias, name, status, version, created_at) "
                        + "values (?, ?, ?, 'ACTIVE', 0, now())",
                orgId, "org-" + orgId.toString().substring(0, 13), "Org " + orgId);
        UUID roleId = UUID.randomUUID();
        TenantContext.runAs(orgId, () -> {
            jdbc.update("insert into org_role (id, org_id, code, name, system_role, version, created_at) "
                    + "values (?, ?, 'FLEET', 'Fleet', false, 0, now())", roleId, orgId);
            jdbc.update("insert into role_permission (role_id, permission) values (?, 'ORG_READ')", roleId);
            jdbc.update("insert into membership (id, org_id, person_id, role_id, status, version, created_at) "
                    + "values (?, ?, ?, ?, 'ACTIVE', 0, now())", UUID.randomUUID(), orgId, personId, roleId);
            // Qualified, so it lands in the platform schema from inside the tenant's axis — the pair
            // OrgProjectionWriter writes in one transaction for every real seat (ADR 0010 §2.1).
            jdbc.update("insert into platform.org_membership_index (person_id, org_id, status) "
                    + "values (?, ?, 'ACTIVE') on conflict (person_id, org_id) do nothing",
                    personId, orgId);
        });
    }
}
