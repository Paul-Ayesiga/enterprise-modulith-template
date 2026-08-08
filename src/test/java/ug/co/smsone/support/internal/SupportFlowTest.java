package ug.co.smsone.support.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
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
import io.micrometer.core.instrument.MeterRegistry;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;

/**
 * The full support lifecycle: a tenant opens a ticket, the platform assigns and replies (public
 * reply notifies the opener; an internal note stays invisible to the tenant), and an SLA breach
 * escalates automatically — priority bumped, counter incremented, the ticket flagged. The tenant
 * only ever sees public messages.
 *
 * <p>Both sides of the desk are people: the tenant member and the platform operator are each a
 * {@code person} with the {@code external_identity} link their token resolves through, because
 * openers, authors and assignees are {@code person.id}s — a token that reaches no person is refused
 * on the write paths, and an assignment names an operator's id rather than a login name.
 */
@AutoConfigureMockMvc
class SupportFlowTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SlaEscalationJob escalationJob;

    @Autowired
    private MeterRegistry meters;

    @Test
    void openAssignReplyWithInternalNoteAndTenantSeesOnlyPublic() throws Exception {
        UUID orgId = seedOrg();
        Person opener = seedMember(orgId, "tenant-op", "TICKET_READ", "TICKET_WRITE");
        Person operator = seedOperator("support"); // a platform person, member of no tenant

        MvcResult opened = mockMvc.perform(post("/api/v1/orgs/{orgId}/tickets", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"Cannot see documents\",\"priority\":\"P2\"}")
                        .with(member(orgId, opener)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.attributes.status").value("OPEN"))
                .andReturn();
        String ticketId = JsonPath.read(opened.getResponse().getContentAsString(), "$.data.id");

        // Platform assigns and adds an INTERNAL note, then a PUBLIC reply.
        mockMvc.perform(post("/api/v1/admin/tickets/{id}/assignment", ticketId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneePersonId\":\"" + operator.id() + "\"}")
                        .with(support(operator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.status").value("IN_PROGRESS"));
        mockMvc.perform(post("/api/v1/admin/tickets/{id}/messages", ticketId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"checking their doc perms\",\"internal\":true}")
                        .with(support(operator)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/admin/tickets/{id}/messages", ticketId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Please try again now.\",\"internal\":false}")
                        .with(support(operator)))
                .andExpect(status().isCreated());

        // The tenant sees ONLY the public reply (not the internal note).
        mockMvc.perform(get("/api/v1/orgs/{orgId}/tickets/{id}/messages", orgId, ticketId)
                        .with(member(orgId, opener)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].attributes.body").value("Please try again now."));
        // Platform sees both.
        mockMvc.perform(get("/api/v1/admin/tickets/{id}/messages", ticketId).with(support(operator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));

        // The opener was notified of the public reply (queued IN_APP delivery). An IN_APP recipient is
        // the opener's person.id — the only durable answer to "who", now that a subject is a link.
        assertThat(jdbc.queryForObject(
                "select count(*) from notification_delivery where channel = 'IN_APP' and recipient = ?",
                Integer.class, opener.id().toString())).isGreaterThanOrEqualTo(1);
    }

    @Test
    void anSlaBreachEscalatesTheTicket() throws Exception {
        UUID orgId = seedOrg();
        Person opener = seedMember(orgId, "tenant-sla", "TICKET_READ", "TICKET_WRITE");
        Person operator = seedOperator("support-sla");
        MvcResult opened = mockMvc.perform(post("/api/v1/orgs/{orgId}/tickets", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"urgent\",\"priority\":\"P3\"}")
                        .with(member(orgId, opener)))
                .andExpect(status().isCreated()).andReturn();
        String ticketId = JsonPath.read(opened.getResponse().getContentAsString(), "$.data.id");

        double before = breachedCount();
        // Age the resolution due date past now so the breach scan catches it. `ticket` is TENANT-tier
        // (ADR 0010 §2) and this one was opened through the org route, so it is in the tenant's schema
        // — the harness's PLATFORM pin cannot see it. `shedlock` is platform-tier and stays on that pin.
        TenantContext.runAs(orgId, () -> jdbc.update(
                "update ticket set resolution_due_at = now() - interval '1 hour' where id = ?::uuid", ticketId));
        jdbc.update("update shedlock set lock_until = timestamp '1970-01-01 00:00:00' where name = ?",
                "support-sla-escalation");
        escalationJob.escalateBreaches();

        mockMvc.perform(get("/api/v1/admin/tickets/{id}", ticketId).with(support(operator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.escalated").value(true))
                .andExpect(jsonPath("$.data.attributes.priority").value("P2")); // bumped from P3
        assertThat(breachedCount()).isEqualTo(before + 1);

        // Running again does NOT double-escalate (already flagged).
        jdbc.update("update shedlock set lock_until = timestamp '1970-01-01 00:00:00' where name = ?",
                "support-sla-escalation");
        escalationJob.escalateBreaches();
        assertThat(breachedCount()).isEqualTo(before + 1);
    }

    private double breachedCount() {
        return meters.find("smsone.support.breached").counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
    }

    /** The operator's token: the platform role, plus the subject their person is linked under. */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor support(Person operator) {
        return jwt().jwt(t -> t.issuer(EdgeSeed.ISSUER).subject(operator.subject()))
                .authorities(new SimpleGrantedAuthority("ROLE_platform-support"));
    }

    /** The tenant's token: the linked subject, its issuer, and the alias-keyed organization claim. */
    private org.springframework.test.web.servlet.request.RequestPostProcessor member(UUID orgId, Person member) {
        return jwt().jwt(token -> token.issuer(EdgeSeed.ISSUER).subject(member.subject())
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
     * The platform operator: a person of their own with no membership anywhere. They need one because
     * a reply records its author's {@code person.id} and an assignment names the assignee's — the desk
     * refuses a caller who is nobody, however platform their role.
     */
    private Person seedOperator(String label) {
        String subject = label + "-" + UUID.randomUUID();
        return new Person(EdgeSeed.person(jdbc, subject), subject);
    }

    /**
     * A person with an ACTIVE membership in {@code orgId} carrying {@code permissions}. The membership
     * names the {@code person.id}; the subject lives only on the {@code external_identity} link, and is
     * unique per seed because that link is unique per (provider, issuer, subject) container-wide.
     */
    private Person seedMember(UUID orgId, String label, String... permissions) {
        String subject = label + "-" + UUID.randomUUID();
        UUID personId = EdgeSeed.person(jdbc, subject);
        UUID roleId = UUID.randomUUID();
        // The seat is TENANT-tier — org_role, role_permission and membership all are (ADR 0010 §2) —
        // so it takes one span on this organization's axis; the person above is platform-tier and
        // stays on the harness's PLATFORM pin.
        TenantContext.runAs(orgId, () -> {
            jdbc.update("insert into org_role (id, org_id, code, name, system_role, version, created_at) "
                    + "values (?, ?, ?, 'SupRole', false, 0, now())", roleId, orgId,
                    "SUP_" + label.toUpperCase().replace('-', '_'));
            for (String permission : permissions) {
                jdbc.update("insert into role_permission (role_id, permission) values (?, ?)", roleId, permission);
            }
            jdbc.update("insert into membership (id, org_id, person_id, role_id, status, version, created_at) "
                    + "values (?, ?, ?, ?, 'ACTIVE', 0, now())", UUID.randomUUID(), orgId, personId, roleId);
            // Qualified, so it lands in the platform schema from inside the tenant's axis — the pair
            // OrgProjectionWriter writes in one transaction for every real seat (ADR 0010 §2.1).
            jdbc.update("insert into platform.org_membership_index (person_id, org_id, status) "
                    + "values (?, ?, 'ACTIVE') on conflict (person_id, org_id) do nothing",
                    personId, orgId);
        });
        return new Person(personId, subject);
    }

    /** A seeded person: the id the desk records, and the token subject that resolves to it. */
    private record Person(UUID id, String subject) {
    }
}
