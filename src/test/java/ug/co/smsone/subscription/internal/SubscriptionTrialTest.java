package ug.co.smsone.subscription.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;

/**
 * Trial mode on paid plans and the pause-to-read-only rule. A PRO trial grants full access; when it
 * lapses the expiry job PAUSES the subscription and org-scoped writes answer 402 while reads still
 * pass; assigning a plan lifts the pause. FREE cannot be trialed.
 *
 * <p><strong>The MockMvc calls need no axis of their own; the three direct ones do</strong>
 * (ADR 0010 §2). {@code /api/v1/orgs/…} pins through {@code CurrentUserFilter} and
 * {@code /api/v1/admin/orgs/{orgId}/…} through {@code AdminSubscriptionController}'s own
 * {@code callAs}. What is left over is this class's own database work — the seat it seeds, the
 * {@code org_subscription} row it ages, and {@code expireTrials()} driven straight off the service —
 * and all three touch tenant-tier tables that the harness's platform pin cannot see. The
 * {@code plan} rows those paths read alongside them are platform-tier and name their schema in the
 * mapping, so one tenant span covers each.
 */
@AutoConfigureMockMvc
class SubscriptionTrialTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SubscriptionService subscriptions;

    @Autowired
    private MeterRegistry meters;

    @Test
    void paidTrialGrantsAccessThenExpiryPausesToReadOnlyAndAssignResumes() throws Exception {
        UUID orgId = seedOrg();
        String operator = "trial-op-" + UUID.randomUUID();
        seedMember(orgId, operator, "ORG_READ", "TICKET_WRITE", "SUBSCRIPTION_READ");
        double before = expiredCount();

        // Platform starts a 14-day PRO trial.
        mockMvc.perform(post("/api/v1/admin/orgs/{orgId}/subscription/trial", orgId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"plan\":\"PRO\",\"days\":14}")
                        .with(admin()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.attributes.status").value("TRIALING"))
                .andExpect(jsonPath("$.data.attributes.planCode").value("PRO"))
                .andExpect(jsonPath("$.data.attributes.trialEndsAt").isNotEmpty());

        // During the trial the org may WRITE (open a support ticket).
        mockMvc.perform(post("/api/v1/orgs/{orgId}/tickets", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"hi\",\"priority\":\"P3\"}")
                        .with(member(orgId, operator)))
                .andExpect(status().isCreated());

        // Age the trial into the past and run the expiry scan. Both on a tenant axis:
        // `org_subscription` is tenant-tier, and expireTrials is a cross-org sweep of it — which is why
        // TrialExpiryJob drives it under the pooled-tenant pin rather than the platform one. Pinned to
        // this org here because it is the org under test and, while there is one tenant schema, its
        // axis and the pool's are the same schema.
        TenantContext.runAs(orgId, () ->
                jdbc.update("update org_subscription set trial_ends_at = now() - interval '1 day' "
                        + "where org_id = ? and deleted_at is null", orgId));
        int paused = TenantContext.callAs(orgId, subscriptions::expireTrials);
        assertThat(paused).isGreaterThanOrEqualTo(1);
        assertThat(expiredCount()).isEqualTo(before + 1);
        // Idempotent — a second scan does not re-pause (the row is no longer TRIALING).
        TenantContext.callAs(orgId, subscriptions::expireTrials);
        assertThat(expiredCount()).isEqualTo(before + 1);

        // PAUSED: org-scoped writes are refused 402, the tenant sees PAUSED, reads still pass.
        mockMvc.perform(post("/api/v1/orgs/{orgId}/tickets", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"again\",\"priority\":\"P3\"}")
                        .with(member(orgId, operator)))
                .andExpect(status().isPaymentRequired());
        mockMvc.perform(get("/api/v1/orgs/{orgId}/subscription", orgId).with(member(orgId, operator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.status").value("PAUSED"));

        // Assigning a plan lifts the pause — writes flow again.
        mockMvc.perform(put("/api/v1/admin/orgs/{orgId}/subscription", orgId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"plan\":\"PRO\"}")
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.status").value("ACTIVE"));
        mockMvc.perform(post("/api/v1/orgs/{orgId}/tickets", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"ok now\",\"priority\":\"P3\"}")
                        .with(member(orgId, operator)))
                .andExpect(status().isCreated());
    }

    @Test
    void freePlanCannotBeTrialed() throws Exception {
        mockMvc.perform(post("/api/v1/admin/orgs/{orgId}/subscription/trial", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"plan\":\"FREE\"}")
                        .with(admin()))
                .andExpect(status().isUnprocessableContent());
    }

    private double expiredCount() {
        return meters.find("smsone.subscription.trial_expired").counters().stream()
                .mapToDouble(Counter::count).sum();
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(t -> t.subject("platform-admin-1"))
                .authorities(new SimpleGrantedAuthority("ROLE_platform-admin"),
                        new SimpleGrantedAuthority("ROLE_platform-support"));
    }

    /**
     * A token that resolves to both the person and the tenant: {@code sub} through
     * {@code external_identity}, the alias-keyed {@code organization} claim through
     * {@code external_organization} — so the claim carries the PROVIDER's org id (read back from the
     * link), not the {@code organization.id} the URL uses. {@code iss} must be the seeded issuer, or
     * the write that should be refused 402 would be refused 403 instead and prove nothing.
     */
    private RequestPostProcessor member(UUID orgId, String subject) {
        Map<String, Object> link = jdbc.queryForMap(
                "select external_org_id, external_alias from external_organization where organization_id = ?",
                orgId);
        return jwt().jwt(token -> token.subject(subject)
                .claim("iss", EdgeSeed.ISSUER)
                .claim("organization", Map.of(String.valueOf(link.get("external_alias")),
                        Map.of("id", String.valueOf(link.get("external_org_id"))))));
    }

    /** The tenant the trial is granted to: an {@code organization} plus its provider link. */
    private UUID seedOrg() {
        return EdgeSeed.organization(jdbc, "kc-org-" + UUID.randomUUID(), "acme-" + UUID.randomUUID());
    }

    /**
     * A real person, linked to {@code subject}, holding {@code permissions} in {@code orgId}.
     *
     * <p>Two tiers. {@code person} and its {@code external_identity} link are platform-tier and take
     * the harness's own pin — a human belongs to no single tenant, which is the whole reason they stayed
     * behind in the split. {@code org_role}, {@code role_permission} and {@code membership} are the
     * tenant's, so they are seeded on that org's axis; {@code EdgeSeed.member} declares the same span
     * for the same rows, and this method only spells it out because it needs the permission rows too.
     */
    private void seedMember(UUID orgId, String subject, String... permissions) {
        UUID personId = EdgeSeed.person(jdbc, subject);
        UUID roleId = UUID.randomUUID();
        TenantContext.runAs(orgId, () -> {
            jdbc.update("insert into org_role (id, org_id, code, name, system_role, version, created_at) "
                    + "values (?, ?, ?, 'TrialRole', false, 0, now())", roleId, orgId,
                    "TRIAL_" + subject.toUpperCase().replace('-', '_'));
            for (String permission : permissions) {
                jdbc.update("insert into role_permission (role_id, permission) values (?, ?)",
                        roleId, permission);
            }
            jdbc.update("insert into membership (id, org_id, person_id, role_id, status, version, created_at) "
                    + "values (?, ?, ?, ?, 'ACTIVE', 0, now())", UUID.randomUUID(), orgId, personId, roleId);
            // The platform-side routing row every real membership carries (ADR 0010 §2.1) — MemberService
            // and OrgProjectionWriter write the pair in one transaction, so a fixture that wrote only the
            // membership would be seeding a state the application cannot produce. Qualified, so it lands
            // in `platform` from inside the tenant's axis; same statement EdgeSeed.member uses.
            jdbc.update("insert into platform.org_membership_index (person_id, org_id, status) "
                    + "values (?, ?, 'ACTIVE') on conflict (person_id, org_id) do nothing",
                    personId, orgId);
        });
    }
}
