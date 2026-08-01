package ug.co.smsone.exchange.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * Recurring exports: create validates the cron and gates on the handler's export permission; the
 * firing job submits AS the requester and — the revocation-safety contract — DISABLES a schedule
 * whose requester lost that permission instead of exporting on.
 */
@AutoConfigureMockMvc
@Import(ExchangeTestSupport.class)
class ExchangeScheduleTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ExchangeScheduleFiringJob firingJob;

    @Autowired
    private ExchangeWorker worker;

    @Autowired
    private ExchangeTestSupport.CountingExchangeHandler handler;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private org.springframework.cache.CacheManager cacheManager;

    @Autowired
    private ug.co.smsone.shared.security.OrgAuthorization authorization;

    @BeforeEach
    void reset() {
        handler.reset();
        jdbc.update("delete from exchange_job");
        jdbc.update("delete from exchange_schedule");
    }

    @Test
    void aScheduleFiresAsItsRequesterAndDisablesOnRevocation() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID roleId = seedMember(orgId, "scheduler-1", "ORG_READ", "MEMBER_READ");

        MvcResult created = mockMvc.perform(post("/api/v1/orgs/{orgId}/exchange/schedules", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"handler\":\"test-counter\",\"format\":\"CSV\",\"cron\":\"0 0 2 * * *\"}")
                        .with(member(orgId, "scheduler-1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.attributes.enabled").value(true))
                .andReturn();
        String scheduleId = JsonPath.read(created.getResponse().getContentAsString(), "$.data.id");

        // Make it due and fire: a job appears, attributed to the requester, and next_run_at moves on.
        jdbc.update("update exchange_schedule set next_run_at = now() - interval '1 minute' where id = ?::uuid",
                scheduleId);
        firingJob.fireDueSchedules();
        assertThat(jdbc.queryForObject(
                "select count(*) from exchange_job where org_id = ? and requester = 'scheduler-1'",
                Integer.class, orgId)).isEqualTo(1);
        assertThat(worker.drainOnce()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select status from exchange_job where org_id = ?", String.class, orgId))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject(
                "select next_run_at > now() from exchange_schedule where id = ?::uuid",
                Boolean.class, scheduleId)).isTrue();

        // Revoke the requester's export permission (member:read) and make it due again. The API
        // path's RolePermissionsChanged event evicts this cache; a raw-SQL revoke must do it here.
        jdbc.update("delete from role_permission where role_id = ? and permission = 'MEMBER_READ'", roleId);
        java.util.Objects.requireNonNull(cacheManager.getCache("org-permissions")).clear();
        assertThat(authorization.hasPermission("scheduler-1", orgId, "member:read"))
                .as("probe: the revocation is visible to the port after eviction").isFalse();
        jdbc.update("update exchange_schedule set next_run_at = now() - interval '1 minute' where id = ?::uuid",
                scheduleId);
        firingJob.fireDueSchedules();
        assertThat(jdbc.queryForObject(
                "select enabled from exchange_schedule where id = ?::uuid", Boolean.class, scheduleId))
                .as("a revoked requester stops the schedule loudly").isFalse();
        assertThat(jdbc.queryForObject(
                "select count(*) from exchange_job where org_id = ?", Integer.class, orgId))
                .as("no second job was submitted").isEqualTo(1);
    }

    @Test
    void aBadCronIsA422AtCreateNeverAFiringTimeSurprise() throws Exception {
        UUID orgId = UUID.randomUUID();
        seedMember(orgId, "scheduler-2", "ORG_READ", "MEMBER_READ");
        mockMvc.perform(post("/api/v1/orgs/{orgId}/exchange/schedules", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"handler\":\"test-counter\",\"format\":\"CSV\",\"cron\":\"not-a-cron\"}")
                        .with(member(orgId, "scheduler-2")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].source.pointer").value("/data/attributes/cron"));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor member(UUID orgId, String subject) {
        return jwt().jwt(token -> token.subject(subject)
                .claim("organization", Map.of("acme", Map.of("id", orgId.toString()))));
    }

    private UUID seedMember(UUID orgId, String subject, String... permissions) {
        jdbc.update("insert into organization (id, kc_org_id, alias, name, status, version, created_at) "
                        + "values (?, ?, ?, ?, 'ACTIVE', 0, now()) "
                        + "on conflict (kc_org_id) where deleted_at is null do nothing",
                UUID.randomUUID(), orgId, "org-" + orgId.toString().substring(0, 13), "Org " + orgId);
        UUID roleId = UUID.randomUUID();
        jdbc.update("insert into org_role (id, org_id, code, name, system_role, version, created_at) "
                + "values (?, ?, ?, 'SchedRole', false, 0, now())", roleId, orgId,
                "SCH_" + subject.toUpperCase().replace('-', '_'));
        for (String permission : permissions) {
            jdbc.update("insert into role_permission (role_id, permission) values (?, ?)", roleId, permission);
        }
        jdbc.update("insert into membership (id, org_id, user_subject, role_id, status, version, created_at) "
                + "values (?, ?, ?, ?, 'ACTIVE', 0, now())", UUID.randomUUID(), orgId, subject, roleId);
        return roleId;
    }
}
