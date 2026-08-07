package ug.co.smsone.support.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Duration;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;

/**
 * A per-org SLA override changes the due dates a ticket is stamped with at open, and clearing it
 * falls the org back to the seeded per-priority default (P3 = 3 days resolution).
 *
 * <p>The tenant is a seeded {@code organization} plus the provider link its {@code organization} claim
 * resolves through, and the member is a {@code person} plus the {@code external_identity} link the
 * token's (iss, sub) resolves through — opening a ticket needs a person, so a token that reaches none
 * is refused before any SLA is consulted.
 */
@AutoConfigureMockMvc
class SlaOverrideTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void anOrgSlaOverrideRetargetsDueDatesAtOpenAndClearingFallsBack() throws Exception {
        UUID orgId = seedOrg();
        String operator = seedMember(orgId, "sla-op", "TICKET_READ", "TICKET_WRITE");

        // Platform sets a tight P3 SLA for this org: 5m first response, 30m resolution.
        mockMvc.perform(put("/api/v1/admin/orgs/{orgId}/sla/{priority}", orgId, "P3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstResponseMinutes\":5,\"resolutionMinutes\":30}")
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.resolutionMinutes").value(30));

        // A P3 ticket opened now is due within the override window (30m ≪ the seeded 3-day default).
        Instant due = resolutionDueOfNewTicket(orgId, operator);
        assertThat(due).isBefore(Instant.now().plus(Duration.ofHours(2)));

        // Clearing the override falls the org back to the seeded P3 default (3-day resolution).
        mockMvc.perform(delete("/api/v1/admin/orgs/{orgId}/sla/{priority}", orgId, "P3").with(admin()))
                .andExpect(status().isNoContent());
        Instant fallbackDue = resolutionDueOfNewTicket(orgId, operator);
        assertThat(fallbackDue).isAfter(Instant.now().plus(Duration.ofDays(1)));
    }

    @Test
    void aMissingMinuteFieldIs422() throws Exception {
        UUID orgId = UUID.randomUUID();
        mockMvc.perform(put("/api/v1/admin/orgs/{orgId}/sla/{priority}", orgId, "P2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstResponseMinutes\":10}")
                        .with(admin()))
                .andExpect(status().isUnprocessableContent());
    }

    private Instant resolutionDueOfNewTicket(UUID orgId, String operator) throws Exception {
        MvcResult opened = mockMvc.perform(post("/api/v1/orgs/{orgId}/tickets", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"sla check\",\"priority\":\"P3\"}")
                        .with(member(orgId, operator)))
                .andExpect(status().isCreated()).andReturn();
        String ticketId = JsonPath.read(opened.getResponse().getContentAsString(), "$.data.id");
        Timestamp due = jdbc.queryForObject(
                "select resolution_due_at from ticket where id = ?::uuid", Timestamp.class, ticketId);
        return due.toInstant();
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(t -> t.subject("platform-admin-1"))
                .authorities(new SimpleGrantedAuthority("ROLE_platform-admin"),
                        new SimpleGrantedAuthority("ROLE_platform-support"));
    }

    /** The tenant's own token: the linked subject, the issuer that link is under, and the org claim. */
    private RequestPostProcessor member(UUID orgId, String subject) {
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
     * token subject that reaches them. The membership names the {@code person.id} — the subject only
     * exists on the {@code external_identity} link {@link EdgeSeed#person} writes beside the person,
     * and is made unique per seed because that link is unique per (provider, issuer, subject) across
     * the whole shared container.
     */
    private String seedMember(UUID orgId, String label, String... permissions) {
        String subject = label + "-" + UUID.randomUUID();
        UUID personId = EdgeSeed.person(jdbc, subject);
        UUID roleId = UUID.randomUUID();
        jdbc.update("insert into org_role (id, org_id, code, name, system_role, version, created_at) "
                + "values (?, ?, ?, 'SlaRole', false, 0, now())", roleId, orgId,
                "SLA_" + label.toUpperCase().replace('-', '_'));
        for (String permission : permissions) {
            jdbc.update("insert into role_permission (role_id, permission) values (?, ?)", roleId, permission);
        }
        jdbc.update("insert into membership (id, org_id, person_id, role_id, status, version, created_at) "
                + "values (?, ?, ?, ?, 'ACTIVE', 0, now())", UUID.randomUUID(), orgId, personId, roleId);
        return subject;
    }
}
