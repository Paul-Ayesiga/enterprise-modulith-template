package ug.co.smsone.testsupport;

import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ug.co.smsone.shared.persistence.TenantMigrationRunner;
import ug.co.smsone.shared.persistence.TenantMigrationRunner.Manifest;
import ug.co.smsone.shared.persistence.TenantMigrationRunner.Mode;

/**
 * Builds the suite's database the same way production builds it — by running
 * {@link TenantMigrationRunner} against the singleton container before any Spring context starts.
 *
 * <h2>Why this exists now and did not before</h2>
 *
 * <p>Through Phase 3 the tenant tables were replayed into {@code tenant_pool} by
 * {@code db/migration/bootstrap/V9999__tenant_pool.sql}, a generated scaffold appended to the platform
 * run because Spring Boot autoconfigures exactly one Flyway and there was nowhere else to put the
 * tenant sequence. That file carried its own expiry note; Phase 4 deleted it, its directory and its
 * entry in {@code spring.flyway.locations}, because the runner it stood in for now exists. Boot's
 * Flyway therefore builds {@code platform} and nothing else, and without this hook every one of the 89
 * database-touching test classes would fail on its first unqualified table with
 * {@code relation "ticket" does not exist}.
 *
 * <h2>Why the real runner and not a test-only replay</h2>
 *
 * <p>A fixture that built {@code tenant_pool} some other way would leave the runner exercised only by
 * its own tests, which is the weakest place for the thing a release now depends on to be proven. This
 * way every {@code ./gradlew test} run replays the exact production sequence — platform first, then
 * the fan-out, against a genuinely empty database — and a runner that could not build a database from
 * nothing would take the whole suite down rather than one test class.
 *
 * <h2>Ordering</h2>
 *
 * <p>Called from {@link AbstractIntegrationTest}'s static initializer, so it runs after
 * {@code POSTGRES.start()} and before the first Spring context is built. Boot's own Flyway then finds
 * the platform sequence already applied and does nothing, which is the same no-op an application pod
 * performs when it starts behind the Kubernetes Job.
 */
public final class TenantSchemaBootstrap {

    /**
     * Four rather than the production eight: the fleet here is one schema, and the pool this opens is
     * sized from the worker count ({@code 2N+2}) against a container the whole suite shares.
     */
    private static final int WORKERS = 4;

    private static final AtomicBoolean DONE = new AtomicBoolean();

    private TenantSchemaBootstrap() {}

    /**
     * Idempotent, and guarded rather than merely idempotent: the runner is cheap to re-run but not
     * free, and several test classes borrow the container without extending
     * {@link AbstractIntegrationTest}.
     */
    public static void ensureMigrated(PostgreSQLContainer container) {
        if (!DONE.compareAndSet(false, true)) {
            return;
        }
        // Unpooled on purpose. The runner pins the baseline search_path on every borrow, so a fresh
        // physical connection per borrow is correct — and a pool here would be a second lifecycle to
        // shut down inside a static initializer that has no shutdown.
        DataSource dataSource = new DriverManagerDataSource(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());
        Manifest manifest = TenantMigrationRunner.fromClasspath(dataSource, WORKERS).run(Mode.MIGRATE);
        if (!manifest.ok()) {
            throw new IllegalStateException(
                    "The tenant migration runner could not build the test database, so nothing below it can"
                            + " be trusted to be testing what it says: " + manifest.report());
        }
    }
}
