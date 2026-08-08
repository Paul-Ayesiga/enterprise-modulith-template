package ug.co.smsone.shared.tenancy.promotion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.shared.tenancy.TenantSchemas;
import ug.co.smsone.shared.tenancy.promotion.TenantTierTables.Plan;
import ug.co.smsone.shared.tenancy.promotion.TenantTierTables.TenantTable;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The gate that stops a promotion silently under-copying: <strong>every table in the tenant schema is
 * in the plan, and every one of them knows how to name one organization's rows.</strong>
 *
 * <p>{@code TenantPromotionTest} proves the move works on eight tables. This proves there is no ninth,
 * nineteenth or twenty-eighth that the promoter would walk straight past. It is the same shape as
 * {@code SoftDeletePurgeJobIntegrationTest.purgeOrderCoversEverySoftDeletableEntity} and exists for the
 * same reason: a list that has to be maintained by hand rots by accretion, and ADR 0010 §5 names
 * {@code OrgExportService}'s twenty-one hand-kept {@code Extract} rows as the thing this design was
 * supposed to make unnecessary.
 *
 * <p>The plan is derived rather than declared, so the strongest assertion available is against the
 * catalogue itself — which is what this makes.
 */
class TenantTierTablesPlanTest extends AbstractIntegrationTest {

    @Autowired
    private TenantTierTables tables;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * Set equality against {@code information_schema}, in both directions. A table missing from the plan
     * is a table whose rows a promotion leaves behind; a table in the plan that no longer exists is a
     * statement that will fail at the worst possible moment, half way through a copy.
     */
    @Test
    void thePlanCoversExactlyTheTablesTheTenantSchemaHolds() {
        List<String> inTheCatalogue = TenantContext.callAsPlatform(() -> jdbc.queryForList("""
                select table_name from information_schema.tables
                 where table_schema = ? and table_type = 'BASE TABLE'
                   and table_name <> 'flyway_schema_history'
                 order by 1
                """, String.class, TenantSchemas.TENANT_POOL));

        assertThat(inTheCatalogue)
                .describedAs("the tenant schema must hold tables, or this test proves nothing")
                .isNotEmpty();
        assertThat(plan().tableNames())
                .describedAs("every tenant-tier table is in the copy plan, and nothing else is —"
                        + " a table absent here is one a promotion silently leaves behind")
                .containsExactlyInAnyOrderElementsOf(inTheCatalogue);
    }

    /**
     * Every table names its organization, and the five with no {@code org_id} of their own do it through
     * their parent.
     *
     * <p>The predicate shape is the assertion: a direct one tests {@code org_id} and binds the
     * organization once, an inherited one is a subquery against the parent and binds it once per level.
     * A table that could name no organization at all does not reach this test — {@code planFor} refuses
     * to build a plan containing one, which is the failure worth having.
     */
    @Test
    void everyTableNamesItsOrganizationDirectlyOrThroughItsParent() {
        Plan plan = plan();
        for (TenantTable table : plan.tables()) {
            assertThat(table.parameters())
                    .describedAs("%s binds the organization at least once", table.name())
                    .isPositive();
            assertThat(table.predicate())
                    .describedAs("%s must select one organization's rows", table.name())
                    .contains("?");
            if (!table.columns().contains("org_id")) {
                assertThat(table.predicate())
                        .describedAs("%s has no org_id, so it must reach one through a parent", table.name())
                        .contains("in (select");
            }
        }

        // The five the ADR names by hand (§2 rows 11, 23, 29, 43, 50). Asserted explicitly because they
        // are the cases a naive `where org_id = ?` copy gets wrong, and because a regression here would
        // otherwise show up as a promoted tenant whose tickets have no messages.
        assertThat(withoutAnOrgColumn(plan)).containsExactlyInAnyOrder(
                "role_permission", "org_group_member", "ticket_message", "exchange_job_error",
                "integration_setting");
    }

    /**
     * Parents before children. The copy inserts in this order so a child never lands before the row it
     * points at, and deletes in the reverse so a child's predicate can still read its parent — the one
     * non-cascading case ({@code membership.role_id → org_role}) makes that a correctness requirement
     * rather than an optimisation.
     */
    @Test
    void theOrderPutsEveryParentBeforeItsChildren() {
        List<String> order = plan().tableNames();

        assertThat(order.indexOf("org_role")).isLessThan(order.indexOf("role_permission"));
        assertThat(order.indexOf("org_role")).isLessThan(order.indexOf("membership"));
        assertThat(order.indexOf("ticket")).isLessThan(order.indexOf("ticket_message"));
        assertThat(order.indexOf("org_group")).isLessThan(order.indexOf("org_group_member"));
        assertThat(order.indexOf("exchange_job")).isLessThan(order.indexOf("exchange_job_error"));
        assertThat(order.indexOf("integration")).isLessThan(order.indexOf("integration_setting"));
        assertThat(order.indexOf("webhook_subscription")).isLessThan(order.indexOf("webhook_delivery"));

        assertThat(plan().deleteOrder().stream().map(TenantTable::name).toList())
                .describedAs("the delete order is the copy order reversed, from one traversal, so the two"
                        + " cannot drift apart")
                .containsExactlyElementsOf(order.reversed());
    }

    private static List<String> withoutAnOrgColumn(Plan plan) {
        return plan.tables().stream()
                .filter(table -> !table.columns().contains("org_id"))
                .map(TenantTable::name)
                .toList();
    }

    private Plan plan() {
        return TenantContext.callAsPlatform(() -> tables.planFor(TenantSchemas.TENANT_POOL));
    }
}
