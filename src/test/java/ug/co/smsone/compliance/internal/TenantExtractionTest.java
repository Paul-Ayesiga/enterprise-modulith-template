package ug.co.smsone.compliance.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The extraction against real Postgres, and every assertion here is one the hand-maintained list could
 * not make.
 *
 * <p>The pairing with {@code GdprBundleTest} is the design: the same {@code webhook_subscription} row
 * is REDACTED there and CARRIED WHOLE here. A change that makes both suites pass with one
 * implementation has broken the boundary — either a disclosure now ships a signing secret, or an
 * extraction now produces a tenant whose webhooks cannot sign.
 */
class TenantExtractionTest extends AbstractIntegrationTest {

    @Autowired
    private TenantExtractionService extraction;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * The property that replaces the 21-row list: the plan is the schema. Compared against the
     * catalog rather than against a list written here, because a list written here would be the very
     * thing that goes stale — a table added in V60 has to appear in BOTH sides of this assertion by
     * itself, or the assertion is just the old bug wearing a test.
     */
    @Test
    void thePlanCoversEveryTableInTheTenantSchemaWithNothingHandMaintained() {
        UUID orgId = UUID.randomUUID();
        List<String> planned = TenantContext.callAs(orgId,
                () -> extraction.plan().stream().map(TenantExtractionService.RoutedTable::table).toList());

        List<String> inTheSchema = TenantContext.callAs(orgId, () -> jdbc.queryForList("""
                select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
                 where n.nspname = current_schema() and c.relkind = 'r'
                   and c.relname <> 'flyway_schema_history'
                """, String.class));

        assertThat(planned).containsExactlyInAnyOrderElementsOf(inTheSchema);
        // The ten the old hand list silently omitted. Named individually and not as a count, so the
        // failure message says which one came back rather than that a number changed.
        assertThat(planned).contains("geo_stamp", "geo_capture_policy", "user_device_trust",
                "feature_flag_org_override", "org_group_member", "role_permission", "ticket_message",
                "exchange_job_error", "integration_setting", "maintenance_window");
    }

    /** Parents before children, so a restore can replay the manifest in order without deferring a FK. */
    @Test
    void theManifestOrdersParentsBeforeTheChildrenThatReferenceThem() {
        UUID orgId = UUID.randomUUID();
        List<String> planned = TenantContext.callAs(orgId,
                () -> extraction.plan().stream().map(TenantExtractionService.RoutedTable::table).toList());

        assertThat(planned.indexOf("ticket")).isLessThan(planned.indexOf("ticket_message"));
        assertThat(planned.indexOf("org_role")).isLessThan(planned.indexOf("role_permission"));
        assertThat(planned.indexOf("org_group")).isLessThan(planned.indexOf("org_group_member"));
        assertThat(planned.indexOf("exchange_job")).isLessThan(planned.indexOf("exchange_job_error"));
        assertThat(planned.indexOf("integration")).isLessThan(planned.indexOf("integration_setting"));
        assertThat(planned.indexOf("webhook_subscription")).isLessThan(planned.indexOf("webhook_delivery"));
    }

    /**
     * The whole reason the two artifacts exist separately. An extraction whose secrets were stripped
     * produces a deployment where every webhook signature fails, and the failure is silent on the
     * sending side — so this assertion is what stops someone "hardening" the extraction by reusing the
     * disclosure bundle's exclusion list.
     */
    @Test
    void theExtractionCarriesTheSigningSecretTheDisclosureBundleRemoves() {
        UUID orgId = UUID.randomUUID();
        UUID neighbor = UUID.randomUUID();
        insertWebhook(orgId, "https://mine.example/hook", "MINE-SECRET");
        insertWebhook(neighbor, "https://theirs.example/hook", "THEIRS-SECRET");

        Map<String, List<Map<String, Object>>> extracted = extract(orgId);

        assertThat(extracted.get("webhook_subscription")).hasSize(1);
        assertThat(extracted.get("webhook_subscription").getFirst())
                .containsEntry("secret", "MINE-SECRET")
                .containsEntry("url", "https://mine.example/hook");
    }

    /**
     * A tenant table with no {@code org_id} of its own, reached through its foreign key. While the
     * tenant is pooled this join is the only thing separating two tenants' messages; once it is siloed
     * the same rows come out of {@code pg_dump -n} with no predicate at all. Both neighbours are
     * seeded, because "returns my rows" and "returns only my rows" are different claims and only the
     * second one matters here.
     */
    @Test
    void childTablesAreRoutedThroughTheForeignKeyToTheirParent() {
        UUID orgId = UUID.randomUUID();
        UUID neighbor = UUID.randomUUID();
        UUID mine = insertTicket(orgId, "Mine");
        UUID theirs = insertTicket(neighbor, "Theirs");
        insertMessage(orgId, mine, "my message");
        insertMessage(neighbor, theirs, "their message");

        Map<String, List<Map<String, Object>>> extracted = extract(orgId);

        assertThat(extracted.get("ticket_message")).hasSize(1);
        assertThat(extracted.get("ticket_message").getFirst()).containsEntry("body", "my message");
    }

    /**
     * The soft-delete difference, stated as a test because it is the one place the two artifacts read
     * as contradictory. The disclosure bundle EXCLUDES deleted rows — handing them back returns data
     * the tenant asked to be rid of. The extraction INCLUDES them: a restore has to be faithful, the
     * retention purge on the far side is what will remove them on its own schedule, and dropping them
     * here would silently shorten the recovery window a tenant already had.
     */
    @Test
    void theExtractionTakesSoftDeletedRowsThatTheDisclosureBundleLeavesBehind() {
        UUID orgId = UUID.randomUUID();
        UUID deleted = insertTicket(orgId, "Shredded");
        TenantContext.runAs(orgId,
                () -> jdbc.update("update ticket set deleted_at = now() where id = ?", deleted));

        assertThat(extract(orgId).get("ticket")).hasSize(1);
    }

    /**
     * An extraction taken from the platform axis would read {@code current_schema() = 'platform'} and
     * dump the person graph and every tenant's routing rows under one org's name. Taken from ANOTHER
     * tenant's axis it would select nothing and report a complete, empty extraction — which is worse,
     * because it looks like success and the operator would have no reason to look again.
     */
    @Test
    void anExtractionRefusesAnyAxisThatIsNotTheTenantsOwn() {
        UUID orgId = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        // The test thread is on PLATFORM (TenantAxisExtension), which is exactly the operator's state.
        assertThatThrownBy(() -> extraction.extract(orgId, (table, row) -> { }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pinned to that tenant");

        assertThatThrownBy(() ->
                TenantContext.runAs(other, () -> extraction.extract(orgId, (table, row) -> { })))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pinned to that tenant");
    }

    /** Runs the extraction on the org's own axis and collects it, which is what a test sink is for. */
    private Map<String, List<Map<String, Object>>> extract(UUID orgId) {
        Map<String, List<Map<String, Object>>> collected = new LinkedHashMap<>();
        TenantContext.runAs(orgId, () -> extraction.extract(orgId,
                (table, row) -> collected.computeIfAbsent(table, key -> new ArrayList<>()).add(row)));
        return collected;
    }

    private void insertWebhook(UUID orgId, String url, String secret) {
        TenantContext.runAs(orgId, () -> jdbc.update(
                "insert into webhook_subscription (id, org_id, url, secret, event_types, status, "
                        + "created_at, updated_at, version) "
                        + "values (?, ?, ?, ?, 'org.member.added', 'ACTIVE', now(), now(), 0)",
                UUID.randomUUID(), orgId, url, secret));
    }

    private UUID insertTicket(UUID orgId, String subject) {
        UUID ticketId = UUID.randomUUID();
        TenantContext.runAs(orgId, () -> jdbc.update("""
                insert into ticket (id, org_id, opener_person_id, subject, priority, status,
                                    first_response_due_at, resolution_due_at, escalated, version,
                                    created_at)
                values (?, ?, ?, ?, 'P3', 'OPEN', now(), now(), false, 0, now())
                """, ticketId, orgId, UUID.randomUUID(), subject));
        return ticketId;
    }

    private void insertMessage(UUID orgId, UUID ticketId, String body) {
        TenantContext.runAs(orgId, () -> jdbc.update("""
                insert into ticket_message (id, ticket_id, author_person_id, body, internal, created_at)
                values (?, ?, ?, ?, false, now())
                """, UUID.randomUUID(), ticketId, UUID.randomUUID(), body));
    }
}
