package ug.co.smsone.shared.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;
import ug.co.smsone.testsupport.TenantSilos;

/**
 * <b>The placement router: the thing that decides which schema a tenant's statement lands in</b>
 * (ADR 0010 §3.1, §7 Phase 5).
 *
 * <p>Phases 1 through 4 built the schema, the migration and the registry while every organization still
 * resolved to {@code tenant_pool}. This class covers the seam that closed that gap and the two failure
 * modes it introduced — a memo that can be stale, and a registry row that can name the wrong schema.
 *
 * <p><b>Every assertion here is schema-qualified or is about a string.</b> That is the whole discipline:
 * a test that seeds and reads through {@code TenantContext} is asking the router the same question
 * twice, and would pass just as happily with no routing at all. {@code SiloTenantProvisioningTest} owns
 * the {@code search_path} the router produces; this owns the registry read behind it and the two
 * callers that reach it without a connection ({@link SplitTables#homeOf}, {@link SplitTables#homes}).
 */
class TenantRoutesTest extends AbstractIntegrationTest {

    @RegisterExtension
    final TenantSilos silos = new TenantSilos();

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * The shipped case, and the one absence has to resolve to: a tenant the registry has never named is
     * pooled and serving. ADR 0010 §4.3 keeps provisioning free of DDL under the pooled policy, and V57's
     * backfill left the pre-registry fleet exactly here, so a router that refused an unknown tenant would
     * refuse most of them.
     */
    @Test
    void anOrganizationTheRegistryHasNeverNamedIsPooled() {
        assertThat(TenantRoutes.homeOf(UUID.randomUUID())).isEqualTo(TenantSchemas.TENANT_POOL);
    }

    /**
     * {@code TenantHome.pool()} and {@code MappedSchemaValidator} both pin {@code new UUID(0, 0)} as
     * "the pooled schema's axis", which only works because a uuid in no {@code organization} row can
     * never be placed anywhere. Asserted rather than assumed: it is the axis every fanned-out sweep
     * enters the pool on.
     */
    @Test
    void theNonOrganizationAxisTheFanOutUsesForThePoolResolvesToThePool() {
        assertThat(TenantRoutes.homeOf(new UUID(0L, 0L))).isEqualTo(TenantSchemas.TENANT_POOL);
    }

    /**
     * <b>The split-table seam, which is the one that writes rows rather than reading them.</b>
     * {@code AuditLogImpl} cannot change axis — it writes inside the caller's transaction, on the
     * caller's connection, and the caller is usually on a different axis than the row's org — so it
     * names the schema through {@link SplitTables#homeOf}. Before the router that method returned
     * {@code tenant_pool} for every organization, and an audit row about a promoted tenant would have
     * been addressed to a schema that tenant had left: a row whose {@code org_id} disagrees with its
     * schema, which ADR 0010 §1 calls the worst failure this design can produce and which
     * {@code pg_dump -n t_<hex>} would then omit from the tenant's compliance record.
     *
     * <p>The insert is the real shape — {@code "insert into " + homeOf(orgId) + ".audit_log"}, the exact
     * concatenation {@code AuditLogImpl} performs — and the readback is qualified, so nothing here can
     * be answered by a {@code search_path}.
     */
    @Test
    void aSplitTableWriteForAPlacedTenantIsAddressedToItsSiloAndNotToThePool() {
        UUID orgId = EdgeSeed.organization(jdbc, "kc-" + UUID.randomUUID(), "routes-" + UUID.randomUUID());
        String silo = silos.place(orgId);

        assertThat(SplitTables.homeOf(orgId)).isEqualTo(silo);
        assertThat(SplitTables.homeOf(null))
                .as("a null org is still the platform's own copy — that half of the rule is unchanged")
                .isEqualTo(SplitTables.PLATFORM);

        UUID entry = UUID.randomUUID();
        jdbc.update("insert into " + SplitTables.homeOf(orgId) + ".audit_log ("
                        + "id, org_id, action, target, occurred_at, version, created_at) "
                        + "values (?, ?, 'ROUTES_PROBE', 'probe', now(), 0, now())",
                entry, orgId);

        assertThat(countIn(silo, entry)).as("the tenant's own compliance record holds it").isEqualTo(1);
        assertThat(countIn(TenantSchemas.TENANT_POOL, entry))
                .as("and the schema it has left holds nothing about it")
                .isZero();
    }

    /**
     * Every home a cross-home sweep could find a row in — the pool and every silo the registry names,
     * platform first. {@code ExchangeJobStore}'s queue claim and the split-table cleanups walk this,
     * and before the router it was two compiled-in constants, so a promoted tenant's queued exchange
     * job was in a schema nothing enumerated.
     */
    @Test
    void everyHomeIncludesThePlatformThePoolAndEverySiloTheRegistryNames() {
        UUID orgId = EdgeSeed.organization(jdbc, "kc-" + UUID.randomUUID(), "homes-" + UUID.randomUUID());
        String silo = silos.place(orgId);

        assertThat(SplitTables.homes())
                .startsWith(SplitTables.PLATFORM)
                .contains(TenantSchemas.TENANT_POOL)
                .contains(silo)
                .doesNotHaveDuplicates();
    }

    /**
     * <b>The memo, and the reason the promoter waits.</b> A route is remembered per process for
     * {@code app.tenancy.route-ttl}, so a placement written behind this process's back is not seen until
     * the memo expires or something drops it. That window is not a defect to be tested away — it is the
     * interval {@code TenantPromoter} holds its freeze for after the flip, and it only has that meaning
     * if the memo really is a memo.
     *
     * <p>So both halves are asserted: the answer survives a registry change this process did not make,
     * and {@link TenantRoutes#forget} — which the placement flip calls — makes the very next resolution
     * read the new row. A router that re-read every time would pass the second and fail the first, and
     * would also be a round trip on every connection borrow in the system.
     */
    @Test
    void aRouteIsRememberedUntilSomethingDropsItWhichIsWhatTheFlipDoes() {
        UUID orgId = EdgeSeed.organization(jdbc, "kc-" + UUID.randomUUID(), "memo-" + UUID.randomUUID());
        String silo = silos.place(orgId);
        assertThat(TenantRoutes.homeOf(orgId)).isEqualTo(silo);

        // Behind the router's back, exactly as another pod's promoter would: the row moves, nothing
        // tells this process. Not through TenantPlacements, because its writes are guarded and this is
        // deliberately the ungoverned case.
        jdbc.update("update platform.tenant_placement set schema_name = ? where org_id = ?",
                TenantSchemas.TENANT_POOL, orgId);

        assertThat(TenantRoutes.homeOf(orgId))
                .as("still the remembered answer — the memo is what keeps the borrow path off the"
                        + " database, and its staleness window is what the promoter's post-flip wait is"
                        + " sized against")
                .isEqualTo(silo);

        TenantRoutes.forget(orgId);

        assertThat(TenantRoutes.homeOf(orgId))
                .as("and the flip's own eviction is what makes the promoting process see it at once")
                .isEqualTo(TenantSchemas.TENANT_POOL);
    }

    /**
     * <b>A placement row that disagrees with itself is refused, never followed.</b> A silo's name is
     * derived from {@code organization.id} with no reads, so a row pairing one org with another's schema
     * cannot be a naming quirk — following it would run this tenant's statements inside another
     * tenant's schema, which is a cross-tenant read that no authorization check in the system would see,
     * because by then the rows genuinely are the ones the query asked for.
     *
     * <p>Failing the borrow is the only safe answer: there is no schema this tenant's statement can
     * legitimately be addressed to, and guessing the pool would write into a third place.
     */
    @Test
    void aPlacementNamingAnotherTenantsSchemaIsRefusedRatherThanRoutedTo() {
        UUID orgId = UUID.randomUUID();
        String someoneElses = TenantSchemas.siloSchema(UUID.randomUUID());
        jdbc.update("""
                insert into platform.tenant_placement
                       (org_id, schema_name, datasource_name, state, updated_at)
                values (?, ?, 'primary', 'ACTIVE', now())
                """, orgId, someoneElses);
        TenantRoutes.forget(orgId);
        try {
            assertThatThrownBy(() -> TenantRoutes.homeOf(orgId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("can only ever be");

            assertThatThrownBy(() -> TenantSchemas.searchPathFor(Tenant.of(orgId)))
                    .as("and the connection-borrow path refuses for the same reason, which is where it"
                            + " actually matters")
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            // The extension's sweep only clears rows whose schema_name is silo-shaped, which this one
            // is — but the memo is this class's to drop, and a stale one naming a schema that never
            // existed would follow the JVM into the next test class.
            jdbc.update("delete from platform.tenant_placement where org_id = ?", orgId);
            TenantRoutes.forget(orgId);
        }
    }

    private int countIn(String schema, UUID entry) {
        Integer count = jdbc.queryForObject(
                "select count(*) from " + schema + ".audit_log where id = ?", Integer.class, entry);
        return count == null ? 0 : count;
    }
}
