package ug.co.smsone.testsupport;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ug.co.smsone.shared.tenancy.TenantRoutes;
import ug.co.smsone.shared.tenancy.TenantSchemas;
import ug.co.smsone.shared.tenancy.placement.TenantPlacement;
import ug.co.smsone.shared.tenancy.placement.TenantPlacements;
import ug.co.smsone.shared.tenancy.placement.TenantProvisioner;

/**
 * <strong>Mixed tenancy in a test: one organization in its own {@code t_<32hex>} schema, everybody else
 * in {@code tenant_pool}</strong> (ADR 0010 §7 Phase 5).
 *
 * <h2>Why the suite needs this before it needs anything else in Phase 5</h2>
 *
 * <p>Every organization the suite has ever created resolves to {@code tenant_pool}, so every fan-out
 * claim in ADR 0010 is currently asserted against a fleet of exactly one schema — which is to say not
 * asserted at all. §8's "Genuinely unknown" item 2 says this about the multi-org person and the same
 * blindness covers silos: the bounded UNION in the platform ticket queue, the per-schema loop in
 * {@code SoftDeletePurgeJob}, the retention jobs, the promoter itself. All of them are green today
 * because there is nothing to fan out over. This fixture is what makes the second schema exist, and
 * nothing else in the phase can be proved without it.
 *
 * <h2>It PLACES a tenant; it does not PROMOTE one</h2>
 *
 * <p>The distinction is the whole contract, and getting it wrong produces the most confusing failure
 * this fixture can cause. {@link #place} creates the schema, migrates it through the real tenant
 * sequence and records the tenant as living there — it copies <strong>no rows</strong>. Moving a tenant
 * whose rows already exist is promotion: a freeze window, {@code CREATE TABLE AS SELECT … WHERE org_id
 * = ?}, verified row counts and a cache eviction (ADR 0010 §6 hop 0→1, Phase 5), and it belongs to the
 * promoter, not here. So the ordering is:
 *
 * <pre>
 * &#64;RegisterExtension
 * final TenantSilos silos = new TenantSilos();
 *
 * UUID siloed = EdgeSeed.organization(jdbc, "kc-" + …, "ext-" + …); // platform-tier rows only
 * silos.place(siloed);                                             // now it lives in t_&lt;hex&gt;
 * EdgeSeed.member(jdbc, siloed, personId, "ADMIN");                 // tenant-tier rows, in the silo
 * </pre>
 *
 * <p>Place first, seed after. Both wrong orders are refused rather than tolerated: an organization that
 * is already ACTIVE somewhere, and an organization that already has rows in {@code tenant_pool}. A
 * fixture that accepted either would leave the tenant's existing rows stranded in a schema it no longer
 * reads, and the test would fail three files away as "the member I just seeded is not there".
 *
 * <h2>The cleanup is the hard half, so it is not the caller's job</h2>
 *
 * <p>A leaked {@code t_<32hex>} schema is not a local mess. It is ~126 relations that every subsequent
 * fan-out has to visit, and it fails tests in other files for reasons that name neither this class nor
 * the test that leaked it: {@code TenancyTierBoundaryTest.everySiloHoldsExactlyTheTenantTierAndNothingElse}
 * compares every silo in the catalogue against {@code tenant_pool}, and
 * {@code TenantSchemaHeadParityTest} requires every tenant schema in the catalogue to sit at the pool's
 * version. That is why this is a JUnit extension and not a helper method: {@link #afterEach} runs after
 * <em>every</em> {@code @AfterEach} in the class and runs whether the test passed, failed or threw
 * mid-way, which is the only cleanup guarantee worth having.
 *
 * <p><strong>It is also registered on {@code AbstractIntegrationTest}</strong>, so the sweep runs after
 * every database-touching test in the suite and not only after the ones that call {@link #place}. That
 * matters under the shipped {@code silo-per-org} placement policy, where every organization created
 * through the service path gets a schema whether the test wanted one or not.
 *
 * <p>The sweep is deliberately <strong>total</strong> rather than a list of what this instance created
 * — {@link #dropEverySilo} drops every silo-shaped schema in the catalogue. A run that died between
 * {@code create schema} and any bookkeeping still gets cleaned, and a class that leaked before this
 * fixture existed is cleaned by the next test that uses it. It is the mechanism
 * {@code SiloTenantProvisioningTest} already used, lifted here so there is one of it.
 *
 * <p><strong>Per test, not per class.</strong> Registering this on a static field would leave the sweep
 * running between the tests of the same class anyway, so a silo built in {@code @BeforeAll} would
 * vanish before the second test. Build the silo inside the test (or in {@code @BeforeEach}, which runs
 * after this extension's {@link #beforeEach}); a fresh schema costs the 200–330 ms of Flyway ADR 0010
 * §4.2 measured, flat to 300 schemas.
 */
public final class TenantSilos implements BeforeEachCallback, AfterEachCallback {

    /**
     * ADR 0010 §3.1's silo name, as a Postgres regex. The same shape {@code TenantSchemas.siloSchema}
     * mints and {@code requireSiloSchema} validates — spelled here as SQL because the sweep asks the
     * catalogue the question, and asking it any other way would mean keeping a list of what was made.
     */
    private static final String SILO_SCHEMA_PATTERN = "^t_[0-9a-f]{32}$";

    private static final String SILO_SCHEMAS_IN_CATALOGUE =
            "select schema_name from information_schema.schemata"
                    + " where schema_name ~ '" + SILO_SCHEMA_PATTERN + "'";

    private static final String FORGET_SILO_PLACEMENTS =
            "delete from platform.tenant_placement where schema_name ~ '" + SILO_SCHEMA_PATTERN + "'";

    /**
     * A cutover row that outlives its test is a refusal, not a leak of bytes: while one exists,
     * {@code TenantPlacements.beginRelocation} declines that tenant's promotions and the migration
     * runner declines its schema (ADR 0011 §7.3) — in whichever unrelated class runs next.
     */
    private static final String FORGET_SILO_CUTOVERS =
            "delete from platform.tenant_cutover where schema_name ~ '" + SILO_SCHEMA_PATTERN + "'";

    /**
     * Captured in {@link #beforeEach} rather than injected, because a field initialised where this
     * extension is declared runs before Spring has injected anything into the test instance. The Spring
     * context is already built by then — {@code SpringExtension} is registered on the class and its
     * callbacks run ahead of an instance-level {@code @RegisterExtension}.
     */
    private ApplicationContext context;

    @Override
    public void beforeEach(ExtensionContext extensionContext) {
        this.context = SpringExtension.getApplicationContext(extensionContext);
    }

    /**
     * Drops every silo the test made, and every silo anything else left behind. Runs after all of the
     * class's own {@code @AfterEach} methods and after a failed or throwing test — see the class note on
     * why a leak is not a local failure.
     */
    @Override
    public void afterEach(ExtensionContext extensionContext) {
        ApplicationContext beans = context;
        if (beans == null) {
            // Resolved again rather than skipped. As a CLASS-level extension on AbstractIntegrationTest
            // this runs for every database-touching test in the suite, so "beforeEach did not manage to
            // capture the context" must not silently become "the sweep did not run" — that is the leak
            // this registration exists to prevent, reintroduced without a symptom. If there is genuinely
            // no context (whatever stopped the test from starting took it too), stay quiet: throwing a
            // second exception over the first would hide it.
            try {
                beans = SpringExtension.getApplicationContext(extensionContext);
            } catch (RuntimeException noContext) {
                return;
            }
        }
        dropEverySilo(beans.getBean(DataSource.class));
    }

    /**
     * Gives {@code orgId} a schema of its own: {@code t_<32hex>} created, migrated through the real
     * tenant migration sequence, and recorded {@code ACTIVE} in {@code platform.tenant_placement}. Every
     * other organization stays pooled.
     *
     * <p><strong>The three steps are in ADR 0010 §4.3's order and that order is the invariant:</strong>
     * reserve the home, build it, and only then announce the tenant. A tenant is never announced before
     * its schema can serve it. Nothing here is a second migrator — the DDL and the Flyway pass are
     * {@code TenantProvisioner.materialize} — the call Phase 5's promotion creates its silo with, and the
     * same {@code TenantSchemaMigrator} a silo signup runs under {@code silo-per-org}. The schema
     * therefore lands at exactly the head {@code tenant_pool} is at, which is what keeps it out of
     * {@code TenantSchemaFloor}'s 503 path and out of {@code TenantSchemaHeadParityTest}'s way.
     *
     * <p>Needs no tenant axis on the calling thread: every statement it issues is either schema-qualified
     * or runs through Flyway on the raw pool, so a {@link NoTenantAxis} class can call it too.
     *
     * @return the silo's schema name, which is also {@code TenantSchemas.siloSchema(orgId)}
     * @throws IllegalStateException if a transaction is open, if the tenant is already serving somewhere
     *     (that move is promotion), or if it already holds rows in {@code tenant_pool} (this fixture
     *     copies nothing)
     */
    public String place(UUID orgId) {
        if (orgId == null) {
            throw new IllegalArgumentException(
                    "a silo is named after an organization id; null names nothing");
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // The same refusal TenantProvisioner.provisionHomeFor makes, for the same two reasons: DDL
            // and Flyway do not nest inside an application transaction, and the registry rows written
            // here would roll back with the test's transaction while the schema they describe would not.
            throw new IllegalStateException(
                    "TenantSilos.place must run OUTSIDE a transaction — it creates a schema and runs"
                            + " Flyway, and the placement rows it writes would roll back with yours while"
                            + " the schema stayed (ADR 0010 §4.3).");
        }
        ApplicationContext beans = requireContext();
        TenantPlacements placements = beans.getBean(TenantPlacements.class);
        TenantProvisioner provisioner = beans.getBean(TenantProvisioner.class);

        String silo = TenantSchemas.siloSchema(orgId);
        refusePlacingATenantThatAlreadyHasPooledRows(beans.getBean(JdbcTemplate.class), orgId);
        if (!placements.reserve(orgId, silo)) {
            // reserve() declines exactly one case — an ACTIVE row — so the registry's own condition is
            // the guard here rather than a copy of the rule that could drift from it.
            TenantPlacement serving = placements.find(orgId).orElseThrow();
            throw new IllegalStateException(
                    "organization " + orgId + " is already serving from schema '" + serving.schemaName()
                            + "', and moving a serving tenant is PROMOTION — a freeze window, a row copy,"
                            + " verified counts and a cache eviction (ADR 0010 §6 hop 0->1). This fixture"
                            + " places a tenant before its rows exist and copies nothing. Either place it"
                            + " before anything announces it, or drive the promoter.");
        }
        // Build the home BEFORE announcing the tenant — §4.3's rule, and the reason this is three calls
        // and not one. materialize() creates the schema, runs the tenant sequence and stamps the version
        // onto every placement row naming that schema, which is the version the floor check reads.
        provisioner.materialize(silo);
        if (!placements.announce(orgId, silo)) {
            throw new IllegalStateException(
                    "organization " + orgId + " could not be announced into '" + silo + "' — something"
                            + " else placed it between this fixture's reserve and its announce, which in a"
                            + " single-threaded test means the registry was written behind its back.");
        }
        // The route memo is what actually sends a statement to this schema, and it is remembered per
        // process for TenantRoutes.routeTtl(). In production that window is covered by the promotion
        // freeze; here there is no freeze and the very next line of the test expects the silo, so the
        // memo is dropped explicitly. Without this a class that touched the org's axis before place()
        // would keep writing into tenant_pool for thirty seconds and the failure would read as "the row
        // I just inserted is not there".
        TenantRoutes.forget(orgId);
        return silo;
    }

    /**
     * <strong>The pre-assertion every fan-out gate needs, and the reason is that without it those tests
     * prove nothing.</strong> A test that seeds through {@code TenantContext.runAs(orgId, …)} and reads
     * back through {@code TenantContext.callAs(orgId, …)} is asking the router the same question twice:
     * if routing were absent, or reverted, both would land in {@code tenant_pool}, the pooled sweep
     * would do the work, and the assertion "the siloed row was purged / escalated / expired" would pass
     * with the silo empty from creation to drop. The tests that were supposed to prove the fan-out
     * reaches a second schema were green with no second schema in play at all.
     *
     * <p>This asks the question the other way — <strong>schema-qualified, so no {@code search_path} is
     * involved</strong> — and answers where the row PHYSICALLY is. Called after seeding and before the
     * job runs, it is what makes the difference between "the fan-out reached the silo" and "there was
     * never a silo" observable.
     *
     * <p>Both halves are asserted. In the silo, because that is the claim; and absent from the pool,
     * because a write that landed in both (or in the pool alone) is exactly the misroute ADR 0010 §1
     * calls the worst failure this design can produce, and a silo-only check would miss it.
     *
     * @param keyColumn the column identifying the row — {@code id} for most tables, the natural key
     *     where there is no surrogate one
     */
    public static void assertRowIsPhysicallyInTheSilo(
            JdbcTemplate jdbc, UUID orgId, String table, String keyColumn, Object key) {
        String silo = TenantSchemas.siloSchema(orgId);
        long inSilo = countIn(jdbc, silo, table, keyColumn, key, orgId);
        long inPool = countIn(jdbc, TenantSchemas.TENANT_POOL, table, keyColumn, key, orgId);
        if (inSilo == 1 && inPool == 0) {
            return;
        }
        throw new AssertionError("the seeded " + table + " row for organization " + orgId
                + " should be in " + silo + " and nowhere else, but " + silo + " holds " + inSilo
                + " and tenant_pool holds " + inPool + "."
                + (inSilo == 0 && inPool == 1
                        ? " The write went to the POOL on the tenant's own axis, which means the router is"
                                + " not resolving platform.tenant_placement — see TenantSchemas"
                                + ".searchPathFor and TenantRoutes. Every fan-out assertion below this"
                                + " line would pass for the wrong reason."
                        : " Two copies of one tenant row, or none at all, is a misroute (ADR 0010 §1)."));
    }

    private static long countIn(
            JdbcTemplate jdbc, String schema, String table, String keyColumn, Object key, UUID orgId) {
        // Qualified on purpose, and it is the one construction that can answer this: routing through
        // search_path would ask the question of whichever schema the router already believes in, which
        // is the thing under test.
        Long count = jdbc.queryForObject("select count(*) from " + schema + "." + table
                + " where " + keyColumn + " = ? and org_id = ?", Long.class, key, orgId);
        return count == null ? 0 : count;
    }

    /**
     * Every {@code t_<32hex>} schema in the catalogue, dropped, and every placement row naming one,
     * forgotten. Public and static so the class that was already doing this by hand shares it rather
     * than keeping a copy, and so a test with no Spring context at all — {@code TenantMigrationRunnerTest}
     * borrows the container directly — can call it with its own pool.
     *
     * <p><strong>One schema per transaction, always.</strong> A tenant schema holds ~439 lockable
     * relations against a cluster's ~6,400 slots, so the fifteenth {@code drop schema … cascade} in one
     * transaction is the measured ceiling and the sixteenth takes the whole block down with "out of
     * shared memory" (ADR 0010 §5.12). Autocommit gives us one per transaction by construction; do not
     * "optimise" this into a DO block.
     *
     * <p>Loud AND complete, the same rule {@code SoftDeletePurgeJob} states: one schema that refuses to
     * drop must not stop the rest from being dropped, and must still be reported. But a sweep that could
     * not finish does <strong>not</strong> go on to forget the registry rows — a surviving schema with
     * no placement row is an orphan nothing can attribute to anything, where a surviving schema that
     * still has its row is a state the migration runner already knows how to report.
     */
    public static void dropEverySilo(DataSource dataSource) {
        // BEFORE the drops, not after: a memo naming a schema that no longer exists would put the next
        // borrow on a search_path pointing at nothing, and the failure would surface in whichever test
        // ran next rather than in the one that leaked. Total, like the sweep itself — the routes it has
        // to invalidate are exactly the ones whose schemas are about to stop existing, and there is no
        // cheaper way to name them than "all of them".
        TenantRoutes.forgetEverything();
        List<String> failures = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            for (String silo : siloSchemas(connection)) {
                try (Statement statement = connection.createStatement()) {
                    // Validated before interpolation even though it came back from the catalogue matching
                    // the silo regex: this string reaches DDL, which cannot bind it as a parameter, and
                    // the guard is one call.
                    statement.execute(
                            "drop schema \"" + TenantSchemas.requireSiloSchema(silo) + "\" cascade");
                } catch (SQLException | RuntimeException failure) {
                    failures.add(silo + ": " + failure);
                }
            }
            if (failures.isEmpty()) {
                // Also clears rows naming a silo that was never created — the registry holds a soft ref
                // and a test may legitimately have written one (V57's header gives the reasons), and the
                // fleet runner reports every such row as a broken tenant on its next pass.
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate(FORGET_SILO_CUTOVERS);
                    statement.executeUpdate(FORGET_SILO_PLACEMENTS);
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("could not sweep the tenant silos this run created", failure);
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException(
                    "tenant silos survived the sweep, so the next test class inherits them and fails"
                            + " somewhere else: " + failures);
        }
    }

    private static List<String> siloSchemas(Connection connection) throws SQLException {
        var schemas = new ArrayList<String>();
        try (Statement statement = connection.createStatement();
                var rows = statement.executeQuery(SILO_SCHEMAS_IN_CATALOGUE)) {
            while (rows.next()) {
                schemas.add(rows.getString(1));
            }
        }
        return schemas;
    }

    /**
     * The wrong-order guard, and it earns its query. {@code EdgeSeed.member} writes {@code org_role} and
     * {@code membership} into whichever schema the org routes to today, so seeding a member and THEN
     * placing the org leaves both rows in {@code tenant_pool} while every later read goes to an empty
     * silo. Nothing throws; the test simply cannot find the seat it just created, and the reason is two
     * files away.
     *
     * <p>Two tables and not nineteen, deliberately: these are the ones every seeded organization has, so
     * this catches the mistake people actually make. It is a smoke check, not a proof that the tenant is
     * empty — the proof that no rows are stranded is the promoter's row-count verification, and this
     * fixture exists precisely because it is not the promoter.
     *
     * <p>Both names are qualified, which is the one place in this repo where writing {@code tenant_pool.}
     * is right rather than a violation of AGENTS §1: the question being asked is about a specific schema,
     * not about the caller's tenant, and resolving it through {@code search_path} would ask it of
     * whatever axis the test happened to be on.
     */
    private static void refusePlacingATenantThatAlreadyHasPooledRows(JdbcTemplate jdbc, UUID orgId) {
        Long pooled = jdbc.queryForObject("""
                select (select count(*) from tenant_pool.membership where org_id = ?)
                     + (select count(*) from tenant_pool.org_role where org_id = ?)
                """, Long.class, orgId, orgId);
        if (pooled != null && pooled > 0) {
            throw new IllegalStateException(
                    "organization " + orgId + " already has " + pooled + " row(s) in tenant_pool, and this"
                            + " fixture copies nothing — placing it now would strand them in a schema the"
                            + " tenant no longer reads. Call place(orgId) BEFORE seeding its tenant-tier"
                            + " rows, or drive the promoter, which copies (ADR 0010 §6 hop 0->1).");
        }
    }

    private ApplicationContext requireContext() {
        if (context == null) {
            throw new IllegalStateException(
                    "TenantSilos needs the Spring test context and has not been given one. Register it on"
                            + " the test class as a non-static field:"
                            + " @RegisterExtension final TenantSilos silos = new TenantSilos();");
        }
        return context;
    }
}
