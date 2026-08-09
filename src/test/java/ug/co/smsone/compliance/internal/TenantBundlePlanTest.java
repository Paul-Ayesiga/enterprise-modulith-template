package ug.co.smsone.compliance.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The platform half of ADR 0010 §6's bundle, and specifically the refusal that keeps it honest.
 *
 * <p><b>Why the refusal is the thing worth testing.</b> On the tenant side, forgetting a table
 * under-copies and the tenant notices — a missing table is a 500 on its next request. On the platform
 * side, forgetting a table is invisible in both directions and stays invisible: nobody misses
 * {@code queue_signal} until two deployments lease the same tenant, and nobody misses {@code plan} until
 * every quota is unlimited and every gated feature 403s at the same time. There is no downstream step
 * that would notice, so the only moment the mistake is catchable is the moment the table is added — and
 * that is what {@link TenantBundlePlan#plan()} makes fail.
 *
 * <p>The refusal has already earned its place once: §6 names four tables as "fresh and empty" and the
 * catalogue now holds six, because {@code platform.queue_signal} (V56) and {@code platform.tenant_freeze}
 * (V58) both landed after the document was written. A hand list would have shipped four.
 */
class TenantBundlePlanTest extends AbstractIntegrationTest {

    @Autowired
    private TenantBundlePlan plan;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * The whole catalogue is judged, and the judgement is checked in BOTH directions. The second half
     * matters as much as the first: an entry left behind by a dropped table is a judgement about
     * nothing, and it would quietly absorb the day somebody re-created a table of that name for a
     * different purpose.
     */
    @Test
    void everyPlatformTableCarriesAJudgementAndEveryJudgementNamesALivingTable() {
        List<String> judged = plan.plan().stream().map(TenantBundlePlan.PlatformTable::table).toList();

        assertThat(judged).containsExactlyInAnyOrderElementsOf(jdbc.queryForList("""
                select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
                 where n.nspname = 'platform' and c.relkind = 'r'
                   and c.relname <> 'flyway_schema_history'
                """, String.class));
    }

    /**
     * A table in {@code platform} with no recorded disposition stops the bundle, naming itself. Created
     * and dropped here rather than asserted from a list, because the property is about a table the
     * document has never seen — which is the only kind that can ever break this rule.
     */
    @Test
    void aPlatformTableWithNoRecordedDispositionRefusesToBePlanned() {
        jdbc.execute("create table platform.undecided_by_anybody (id uuid primary key)");
        try {
            assertThatThrownBy(() -> plan.plan())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("undecided_by_anybody")
                    .hasMessageContaining("no extraction disposition");
        } finally {
            jdbc.execute("drop table platform.undecided_by_anybody");
        }
    }

    /**
     * §6 item 4's refusal, and the reason it is a refusal rather than a warning: an installation whose
     * {@code plan}, {@code plan_entitlement} or {@code sla_policy} is empty is broken HERE AND NOW —
     * {@code PlanSeeder} and {@code SlaPolicySeeder} write them on every boot — and a bundle taken from
     * it ships a snapshot that is worthless in a way the far side cannot report.
     *
     * <p>Asserted positively against the running installation, which is the honest direction: emptying
     * the platform's plan catalogue to watch it throw would break every other test sharing this
     * container, and the refusal's message is already pinned by
     * {@code TenantBundleTest.theCatalogueTravelsWhole…}, which drives its sibling check on a plan id the
     * catalogue genuinely does not hold.
     */
    @Test
    void theCatalogueThisInstallationWouldShipIsWorthShipping() {
        assertThat(plan.requireTheCatalogueIsWorthShipping())
                .describedAs("warnings are for tables an installation may legitimately have empty —"
                        + " translation, setting, feature_flag — and never for the three a seeder writes")
                .allSatisfy(warning -> assertThat(warning)
                        .doesNotContain("platform.plan is empty")
                        .doesNotContain("platform.sla_policy is empty"));

        assertThat(plan.of(BundleDisposition.SNAPSHOT_WHOLE_TABLE).stream()
                .map(TenantBundlePlan.PlatformTable::table))
                .containsExactlyInAnyOrder("plan", "plan_entitlement", "sla_policy", "translation",
                        "setting", "feature_flag");
    }

    /**
     * The list an operator gets surprised by. An extraction's most expensive failure is not a missing
     * row — it is not knowing that the person graph, the metered usage ledger and the platform's own
     * integration credentials were staying behind until something on the far side went quiet.
     */
    @Test
    void whatDoesNotTravelIsEnumeratedWithTheSentenceThatSaysWhatIsLost() {
        List<String> stays = plan.whatDoesNotTravel();

        assertThat(stays).anyMatch(line -> line.startsWith("platform.person_profile — "));
        assertThat(stays).anyMatch(line -> line.startsWith("platform.api_usage_daily — "));
        assertThat(stays).anyMatch(line -> line.contains("integration — split: NULL org is the PLATFORM"));
        assertThat(stays)
                .describedAs("every line carries its reason; a bare table name teaches nobody anything")
                .allSatisfy(line -> assertThat(line).contains(" — "));
    }
}
