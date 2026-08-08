package ug.co.smsone.maintenance.internal;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;

/**
 * A RESTRICT window in effect 503s an org write with Retry-After while reads pass; an ANNOUNCE
 * window never blocks; and a caller can always reach the window's own controls (no self-lockout).
 *
 * <p>The window is matched against {@code CurrentUser.organizationId()}, so the tenant has to be one
 * the edge can actually resolve: a seeded {@code organization} plus the provider link its token's
 * {@code organization} claim names, and a member who is a {@code person} with an identity link.
 */
@AutoConfigureMockMvc
class MaintenanceTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    // A platform-wide (org_id null) window 503s EVERY org write across the shared container — these
    // rows must not outlive the test or they contaminate every other org-write test.
    @org.junit.jupiter.api.AfterEach
    void clearWindows() {
        // BOTH homes. A platform-wide window left behind 503s every org write in the run, and an org
        // window left behind 503s that org's — and since ADR 0010 Phase 2 they are two different tables.
        for (String home : ug.co.smsone.shared.tenancy.SplitTables.homes()) {
            jdbc.update("delete from " + home + ".maintenance_window");
        }
    }

    @Test
    void aRestrictWindowPausesWritesButNotReads() throws Exception {
        UUID orgId = seedOrg();
        String tenant = seedMember(orgId, "tenant-1", "ORG_READ", "ORG_UPDATE", "MEMBER_READ");

        // An org-scoped RESTRICT window in effect right now.
        window(orgId, "RESTRICT", Instant.now().minusSeconds(60), Instant.now().plusSeconds(600));

        // A write (PUT the security policy — an ordinary org write) is 503'd with Retry-After.
        mockMvc.perform(put("/api/v1/orgs/{orgId}/security-policy", orgId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"requireTrustedDevice\":false}")
                        .with(member(orgId, tenant)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.errors[0].code").value("SERVICE_UNAVAILABLE"));

        // A read passes untouched.
        mockMvc.perform(get("/api/v1/orgs/{orgId}/members", orgId).with(member(orgId, tenant)))
                .andExpect(status().isOk());

        // The tenant can still SEE the window (a read), for its banner.
        mockMvc.perform(get("/api/v1/orgs/{orgId}/maintenance?active=true", orgId)
                        .with(member(orgId, tenant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].attributes.mode").value("RESTRICT"));
    }

    @Test
    void anAnnounceWindowNeverBlocks() throws Exception {
        UUID orgId = seedOrg();
        String tenant = seedMember(orgId, "tenant-2", "ORG_READ", "ORG_UPDATE");
        window(orgId, "ANNOUNCE", Instant.now().minusSeconds(60), Instant.now().plusSeconds(600));

        mockMvc.perform(put("/api/v1/orgs/{orgId}/security-policy", orgId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"requireTrustedDevice\":false}")
                        .with(member(orgId, tenant)))
                .andExpect(status().isOk());
    }

    @Test
    void aPlatformWideRestrictWindowCoversEveryOrg() throws Exception {
        UUID orgId = seedOrg();
        String tenant = seedMember(orgId, "tenant-3", "ORG_READ", "ORG_UPDATE");

        mockMvc.perform(post("/api/v1/admin/maintenance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"RESTRICT\",\"message\":\"platform upgrade\","
                                + "\"startsAt\":\"" + Instant.now().minusSeconds(60) + "\","
                                + "\"endsAt\":\"" + Instant.now().plusSeconds(600) + "\"}")
                        .with(admin()))
                .andExpect(status().isCreated());

        mockMvc.perform(put("/api/v1/orgs/{orgId}/security-policy", orgId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"requireTrustedDevice\":false}")
                        .with(member(orgId, tenant)))
                .andExpect(status().isServiceUnavailable());
    }

    /**
     * Seeds into the home the row's {@code org_id} names (ADR 0010 §2 row 25: {@code maintenance_window}
     * is a split table). The harness pins PLATFORM on the test thread, so an unqualified insert would put
     * an org's window in {@code platform.maintenance_window} — where the filter, running on the tenant's
     * axis, would never look for it.
     */
    private void window(UUID orgId, String mode, Instant starts, Instant ends) {
        jdbc.update("insert into " + ug.co.smsone.shared.tenancy.SplitTables.homeOf(orgId)
                + ".maintenance_window (id, org_id, starts_at, ends_at, mode, message, version, created_at) "
                + "values (?, ?, ?, ?, ?, 'scheduled maintenance', 0, now())",
                UUID.randomUUID(), orgId, java.sql.Timestamp.from(starts), java.sql.Timestamp.from(ends), mode);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return jwt().jwt(t -> t.subject("maint-admin"))
                .authorities(new SimpleGrantedAuthority("ROLE_platform-admin"));
    }

    /** The tenant's token: the linked subject, the issuer that link is under, and the org claim. */
    private org.springframework.test.web.servlet.request.RequestPostProcessor member(UUID orgId, String subject) {
        return jwt().jwt(token -> token.issuer(EdgeSeed.ISSUER).subject(subject)
                .claim("organization", orgClaim(orgId)));
    }

    /** A tenant the edge can resolve: an organization plus the provider link its claim names. */
    private UUID seedOrg() {
        return EdgeSeed.organization(jdbc, "kc-org-" + UUID.randomUUID(), "acme-" + UUID.randomUUID());
    }

    /** The alias-keyed {@code organization} claim Keycloak mints, rebuilt from the seeded link. */
    private Map<String, Object> orgClaim(UUID orgId) {
        Map<String, Object> link = jdbc.queryForMap(
                "select external_org_id, external_alias from external_organization where organization_id = ?",
                orgId);
        return Map.of(String.valueOf(link.get("external_alias")),
                Map.of("id", String.valueOf(link.get("external_org_id"))));
    }

    /**
     * A person with an ACTIVE membership in {@code orgId} carrying {@code permissions}; returns the
     * token subject that reaches them. The membership names the {@code person.id} — the subject lives
     * only on the {@code external_identity} link {@link EdgeSeed#person} writes, and is unique per seed
     * because that link is unique per (provider, issuer, subject) across the shared container.
     */
    private String seedMember(UUID orgId, String label, String... permissions) {
        String subject = label + "-" + UUID.randomUUID();
        // person and external_identity are platform-tier, so they ride the harness's platform pin.
        UUID personId = EdgeSeed.person(jdbc, subject);
        UUID roleId = UUID.randomUUID();
        // The seat is not: org_role, role_permission and membership are TENANT-tier (ADR 0010 §2), so
        // they need this org's axis — on the platform pin they would land in, or fail to find, the
        // wrong schema. Same declaration EdgeSeed.member makes for the same three tables.
        ug.co.smsone.shared.tenancy.TenantContext.runAs(orgId, () -> {
            jdbc.update("insert into org_role (id, org_id, code, name, system_role, version, created_at) "
                    + "values (?, ?, ?, 'MaintRole', false, 0, now())", roleId, orgId,
                    "MNT_" + label.toUpperCase().replace('-', '_'));
            for (String permission : permissions) {
                jdbc.update("insert into role_permission (role_id, permission) values (?, ?)", roleId, permission);
            }
            jdbc.update("insert into membership (id, org_id, person_id, role_id, status, version, created_at) "
                    + "values (?, ?, ?, ?, 'ACTIVE', 0, now())", UUID.randomUUID(), orgId, personId, roleId);
        });
        return subject;
    }
}
