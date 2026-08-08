package ug.co.smsone.compliance.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The redacted disclosure bundle against real Postgres: only the target org's rows appear, secret-bearing
 * columns never leave the database, the scope of what counts as "the org's rows" is the LIVE side of
 * {@code deleted_at}, and a bundle that hit its per-table cap says so instead of reading as complete.
 *
 * <p>Its opposite number is {@code TenantExtractionTest}, and the pair is the point: the same
 * {@code webhook_subscription} row is redacted here and carried whole there. If a change ever makes
 * both suites pass with one implementation, that implementation is wrong.
 *
 * <p><b>Seeding names its axis.</b> The bundle spans both tiers now (ADR 0010 §2), so a fixture that
 * wrote everything on one connection would be writing half of it into the wrong schema:
 * {@code webhook_subscription}, {@code document} and {@code ticket} are the tenant's,
 * {@code organization} and {@code api_usage_daily} are the platform's. The endpoint itself does the
 * same thing from the other side — it is called by a platform operator and enters the tenant with
 * {@code TenantContext.callAs}.
 */
@AutoConfigureMockMvc
class GdprBundleTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void exportsTheOrgWithoutSecretsAndWithoutNeighbors() throws Exception {
        UUID target = UUID.randomUUID();
        UUID neighbor = UUID.randomUUID();
        insertWebhook(target, "https://t.example/hook", "ENCRYPTED-SECRET");
        insertWebhook(neighbor, "https://n.example/hook", "NEIGHBOR-SECRET");

        mockMvc.perform(get("/api/v1/admin/compliance/orgs/{orgId}/export", target).with(platformAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("org-export"))
                .andExpect(jsonPath("$.data.attributes.rowCapPerTable").value(1000))
                // Nothing was cut, and the bundle states that rather than leaving it to be assumed.
                .andExpect(jsonPath("$.data.attributes.complete").value(true))
                .andExpect(jsonPath("$.data.attributes.truncatedTables").isEmpty())
                // The recipient is told, in the payload, that this is not the tenant's database.
                .andExpect(jsonPath("$.data.attributes.bundleKind")
                        .value(Matchers.startsWith("REDACTED_DISCLOSURE")))
                .andExpect(jsonPath("$.data.attributes.tables.webhook_subscription.length()").value(1))
                .andExpect(jsonPath("$.data.attributes.tables.webhook_subscription[0].url")
                        .value("https://t.example/hook"))
                .andExpect(jsonPath("$.data.attributes.tables.webhook_subscription[0].secret")
                        .doesNotExist());

        // The disclosure is on the record, and this is the assertion that was missing while it was
        // not: the audit write used to sit in a read-only transaction, where the JPA persist behind it
        // was scheduled and silently dropped at commit. Read on the ORG's axis because audit_log is a
        // split table and this row's org_id is non-null, so it lives in the tenant's copy.
        assertThat(TenantContext.callAs(target, () -> jdbc.queryForObject(
                "select count(*) from audit_log where action = 'compliance.org_exported' and org_id = ?",
                Integer.class, target))).isEqualTo(1);
        // ...and the export is a disclosure, never an extraction. If this ever fires, somebody routed
        // the bundle through TenantExtractionService and both live secrets went out with it.
        assertThat(TenantContext.callAs(target, () -> jdbc.queryForObject(
                "select count(*) from audit_log where action = 'compliance.tenant_extracted' and org_id = ?",
                Integer.class, target))).isZero();
    }

    /**
     * The two halves of the soft-delete decision, which pull in opposite directions and are why the
     * predicate could not simply be applied everywhere.
     *
     * <p>A deleted DOCUMENT is out: the tenant deleted it, no product surface still shows it, and the
     * retention purge is coming for it — handing it back would return data the tenant asked to be rid
     * of. The deleted ORGANIZATION row is in: tenant deletion is soft and stamps only that one row, and
     * offboarding is precisely what happens after a tenant is deleted, so a uniform
     * {@code deleted_at is null} would produce a bundle with no organization in it at all.
     */
    @Test
    void takesLiveRowsOnlyButStillExportsAnAlreadyDeletedTenant() throws Exception {
        UUID target = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        jdbc.update("insert into platform.organization (id, alias, name, status, version, created_at, deleted_at) "
                + "values (?, ?, 'Closed Co', 'SUSPENDED', 0, now(), now())", target, "closed-" + target);
        insertDocument(target, owner, "live.pdf", false);
        insertDocument(target, owner, "shredded.pdf", true);

        mockMvc.perform(get("/api/v1/admin/compliance/orgs/{orgId}/export", target).with(platformAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.tables.organization.length()").value(1))
                .andExpect(jsonPath("$.data.attributes.tables.organization[0].alias")
                        .value("closed-" + target))
                .andExpect(jsonPath("$.data.attributes.tables.document.length()").value(1))
                .andExpect(jsonPath("$.data.attributes.tables.document[0].name").value("live.pdf"));
    }

    /**
     * The defect this endpoint was filed for: a cap that applies itself in silence. With no ORDER BY,
     * WHICH 1,000 rows landed in the bundle was whatever the executor returned — for these tables a
     * parallel seq scan, so it changed with the plan — and nothing in the payload admitted that rows
     * had been left behind at all.
     *
     * <p>{@code api_usage_daily} is the cheapest table to overflow (four columns, keyed by
     * {@code (org_id, day)}), and its {@code requests} counter doubles as the row's position in the
     * series: 1001 days numbered 0…1000. Newest first means the bundle opens at 1000, and the row that
     * gets cut is the OLDEST one — 0 — not an arbitrary one.
     *
     * <p>The days start in 2020 on purpose, and the suite shares one database: {@code UsageExportJob}
     * scans this table for {@code exported = false} within {@code maxBacklogDays} of today, so rows
     * dated near now would become someone else's billing backlog. Do not "modernise" the dates.
     */
    @Test
    void aBundleThatHitTheCapSaysSoAndKeepsTheNewestRows() throws Exception {
        UUID target = UUID.randomUUID();
        // Platform-tier: the usage table stays one copy so the export job's cross-tenant `order by day`
        // fairness keeps working (ADR 0010 §2).
        jdbc.update("insert into platform.api_usage_daily (org_id, day, requests, exported) "
                + "select cast(? as uuid), date '2020-01-01' + s, s, false from generate_series(0, 1000) s",
                target);

        mockMvc.perform(get("/api/v1/admin/compliance/orgs/{orgId}/export", target).with(platformAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributes.complete").value(false))
                .andExpect(jsonPath("$.data.attributes.truncatedTables",
                        Matchers.contains("api_usage_daily")))
                .andExpect(jsonPath("$.data.attributes.tables.api_usage_daily.length()").value(1000))
                .andExpect(jsonPath("$.data.attributes.tables.api_usage_daily[0].requests").value(1000))
                .andExpect(jsonPath("$.data.attributes.tables.api_usage_daily[999].requests").value(1));
    }

    /** Tenant-tier, so it is written on the tenant's own axis — see the class note. */
    private void insertWebhook(UUID orgId, String url, String secret) {
        TenantContext.runAs(orgId, () -> jdbc.update(
                "insert into webhook_subscription (id, org_id, url, secret, event_types, status, "
                        + "created_at, updated_at, version) "
                        + "values (?, ?, ?, ?, 'org.member.added', 'ACTIVE', now(), now(), 0)",
                UUID.randomUUID(), orgId, url, secret));
    }

    /**
     * {@code document} is one of the seven SPLIT tables: an org-owned document belongs to the tenant's
     * copy, and a NULL {@code org_id} would mean a PERSONAL document living in the platform's. The
     * tenant axis is what puts this row in the right one.
     */
    private void insertDocument(UUID orgId, UUID ownerPersonId, String name, boolean deleted) {
        TenantContext.runAs(orgId, () -> jdbc.update(
                "insert into document (id, org_id, owner_person_id, storage_key, name, content_type, "
                        + "size_bytes, source, version, created_at, deleted_at) "
                        + "values (?, ?, ?, ?, ?, 'application/pdf', 12, 'UPLOAD', 0, now(), "
                        + (deleted ? "now()" : "null") + ")",
                UUID.randomUUID(), orgId, ownerPersonId, "doc/" + orgId + "/" + UUID.randomUUID(), name));
    }

    /**
     * No {@code person} behind this token, deliberately: the export is the one compliance endpoint that
     * records no accountable human on a row, so it is reachable by role alone (the audit entry still
     * names whoever the security context resolved).
     */
    private RequestPostProcessor platformAdmin() {
        return jwt().jwt(t -> t.subject("compliance-admin"))
                .authorities(new SimpleGrantedAuthority("ROLE_platform-admin"));
    }
}
