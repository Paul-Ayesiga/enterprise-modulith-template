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
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The full support lifecycle: a tenant opens a ticket, the platform assigns and replies (public
 * reply notifies the opener; an internal note stays invisible to the tenant), and an SLA breach
 * escalates automatically — priority bumped, counter incremented, the ticket flagged. The tenant
 * only ever sees public messages.
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
        UUID orgId = UUID.randomUUID();
        seedMember(orgId, "tenant-op", "ORG_READ");

        MvcResult opened = mockMvc.perform(post("/api/v1/orgs/{orgId}/tickets", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"Cannot see documents\",\"priority\":\"P2\"}")
                        .with(member(orgId, "tenant-op")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.attributes.status").value("OPEN"))
                .andReturn();
        String ticketId = JsonPath.read(opened.getResponse().getContentAsString(), "$.data.id");

        // Platform assigns and adds an INTERNAL note, then a PUBLIC reply.
        mockMvc.perform(post("/api/v1/admin/tickets/{id}/assignment", ticketId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"assignee\":\"support-1\"}")
                        .with(support()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.status").value("IN_PROGRESS"));
        mockMvc.perform(post("/api/v1/admin/tickets/{id}/messages", ticketId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"checking their doc perms\",\"internal\":true}")
                        .with(support()))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/admin/tickets/{id}/messages", ticketId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Please try again now.\",\"internal\":false}")
                        .with(support()))
                .andExpect(status().isCreated());

        // The tenant sees ONLY the public reply (not the internal note).
        mockMvc.perform(get("/api/v1/orgs/{orgId}/tickets/{id}/messages", orgId, ticketId)
                        .with(member(orgId, "tenant-op")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].attributes.body").value("Please try again now."));
        // Platform sees both.
        mockMvc.perform(get("/api/v1/admin/tickets/{id}/messages", ticketId).with(support()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));

        // The opener was notified of the public reply (queued IN_APP delivery).
        assertThat(jdbc.queryForObject(
                "select count(*) from notification_delivery where channel = 'IN_APP' and recipient = 'tenant-op'",
                Integer.class)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void anSlaBreachEscalatesTheTicket() throws Exception {
        UUID orgId = UUID.randomUUID();
        seedMember(orgId, "tenant-sla", "ORG_READ");
        MvcResult opened = mockMvc.perform(post("/api/v1/orgs/{orgId}/tickets", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"urgent\",\"priority\":\"P3\"}")
                        .with(member(orgId, "tenant-sla")))
                .andExpect(status().isCreated()).andReturn();
        String ticketId = JsonPath.read(opened.getResponse().getContentAsString(), "$.data.id");

        double before = breachedCount();
        // Age the resolution due date past now so the breach scan catches it.
        jdbc.update("update ticket set resolution_due_at = now() - interval '1 hour' where id = ?::uuid", ticketId);
        jdbc.update("update shedlock set lock_until = timestamp '1970-01-01 00:00:00' where name = ?",
                "support-sla-escalation");
        escalationJob.escalateBreaches();

        mockMvc.perform(get("/api/v1/admin/tickets/{id}", ticketId).with(support()))
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

    private static org.springframework.test.web.servlet.request.RequestPostProcessor support() {
        return jwt().jwt(t -> t.subject("support-1"))
                .authorities(new SimpleGrantedAuthority("ROLE_platform-support"));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor member(UUID orgId, String subject) {
        return jwt().jwt(token -> token.subject(subject)
                .claim("organization", Map.of("acme", Map.of("id", orgId.toString()))));
    }

    private void seedMember(UUID orgId, String subject, String... permissions) {
        jdbc.update("insert into organization (id, kc_org_id, alias, name, status, version, created_at) "
                        + "values (?, ?, ?, ?, 'ACTIVE', 0, now()) "
                        + "on conflict (kc_org_id) where deleted_at is null do nothing",
                UUID.randomUUID(), orgId, "org-" + orgId.toString().substring(0, 13), "Org " + orgId);
        UUID roleId = UUID.randomUUID();
        jdbc.update("insert into org_role (id, org_id, code, name, system_role, version, created_at) "
                + "values (?, ?, ?, 'SupRole', false, 0, now())", roleId, orgId,
                "SUP_" + subject.toUpperCase().replace('-', '_'));
        for (String permission : permissions) {
            jdbc.update("insert into role_permission (role_id, permission) values (?, ?)", roleId, permission);
        }
        jdbc.update("insert into membership (id, org_id, user_subject, role_id, status, version, created_at) "
                + "values (?, ?, ?, ?, 'ACTIVE', 0, now())", UUID.randomUUID(), orgId, subject, roleId);
    }
}
