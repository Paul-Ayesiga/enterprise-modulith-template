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
import ug.co.smsone.testsupport.EdgeSeed;

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
        UUID orgId = seedOrg();
        UUID scheduler = UUID.randomUUID();
        UUID roleId = seedMember(orgId, scheduler, "ORG_READ", "MEMBER_READ", "EXCHANGE_READ", "EXCHANGE_SUBMIT");

        MvcResult created = mockMvc.perform(post("/api/v1/orgs/{orgId}/exchange/schedules", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"handler\":\"test-counter\",\"format\":\"CSV\",\"cron\":\"0 0 2 * * *\"}")
                        .with(member(orgId, scheduler)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.attributes.enabled").value(true))
                .andReturn();
        String scheduleId = JsonPath.read(created.getResponse().getContentAsString(), "$.data.id");

        // Make it due and fire: a job appears, attributed to the requester, and next_run_at moves on.
        jdbc.update("update exchange_schedule set next_run_at = now() - interval '1 minute' where id = ?::uuid",
                scheduleId);
        firingJob.fireDueSchedules();
        assertThat(jdbc.queryForObject(
                "select count(*) from exchange_job where org_id = ? and requester_person_id = ?",
                Integer.class, orgId, scheduler)).isEqualTo(1);
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
        assertThat(authorization.hasPermission(scheduler, orgId, "member:read"))
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
        UUID orgId = seedOrg();
        UUID scheduler = UUID.randomUUID();
        seedMember(orgId, scheduler, "ORG_READ", "MEMBER_READ", "EXCHANGE_READ", "EXCHANGE_SUBMIT");
        mockMvc.perform(post("/api/v1/orgs/{orgId}/exchange/schedules", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"handler\":\"test-counter\",\"format\":\"CSV\",\"cron\":\"not-a-cron\"}")
                        .with(member(orgId, scheduler)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].source.pointer").value("/data/attributes/cron"));
    }

    /** A tenant the edge can resolve, plus the provider link its {@code organization} claim names. */
    private UUID seedOrg() {
        return EdgeSeed.organization(jdbc, "kc-org-" + UUID.randomUUID(), "acme-" + UUID.randomUUID());
    }

    /** A token that resolves to {@code personId} and to {@code orgId} — both through the link tables. */
    private org.springframework.test.web.servlet.request.RequestPostProcessor member(UUID orgId, UUID personId) {
        Map<String, Object> link = jdbc.queryForMap(
                "select external_org_id, external_alias from external_organization where organization_id = ?",
                orgId);
        return jwt().jwt(token -> token.subject(EdgeSeed.subjectFor(personId))
                .claim("organization", Map.of(String.valueOf(link.get("external_alias")),
                        Map.of("id", String.valueOf(link.get("external_org_id"))))));
    }

    private UUID seedMember(UUID orgId, UUID personId, String... permissions) {
        // The person first: external_identity has a REAL foreign key to it (V10), so the link cannot
        // precede the row it points at.
        jdbc.update("insert into person (id, status, provisioned_at, version, created_at) "
                + "values (?, 'ACTIVE', now(), 0, now())", personId);
        EdgeSeed.link(jdbc, personId, EdgeSeed.subjectFor(personId));
        UUID roleId = UUID.randomUUID();
        jdbc.update("insert into org_role (id, org_id, code, name, system_role, version, created_at) "
                + "values (?, ?, ?, 'SchedRole', false, 0, now())", roleId, orgId,
                "SCH_" + personId.toString().substring(0, 8).toUpperCase());
        for (String permission : permissions) {
            jdbc.update("insert into role_permission (role_id, permission) values (?, ?)", roleId, permission);
        }
        jdbc.update("insert into membership (id, org_id, person_id, role_id, status, version, created_at) "
                + "values (?, ?, ?, ?, 'ACTIVE', 0, now())", UUID.randomUUID(), orgId, personId, roleId);
        return roleId;
    }
}
