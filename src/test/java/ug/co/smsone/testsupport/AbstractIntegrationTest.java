package ug.co.smsone.testsupport;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base for all integration tests: full Spring context against a REAL Postgres via Testcontainers
 * (no H2, no fakes — template rule). Singleton container: started once for the whole JVM, shared by
 * every test class, reaped by Ryuk on exit.
 *
 * <h2>The tenant axis (ADR 0010 §3.4)</h2>
 *
 * <p>{@link TenantAxisExtension} declares the PLATFORM axis on the test thread before each test and
 * takes it off afterwards. Without it nothing here would work: {@code TenantRoutingDataSource} sets
 * {@code search_path} from {@code TenantContext} on every connection borrow, and a test method is not a
 * request — no filter ran above it, so the axis is absent and absent routes to the empty
 * {@code no_tenant} schema. Every unqualified table would fail with {@code relation "…" does not
 * exist}.
 *
 * <p>Today the pin is behaviourally a no-op — Phase 1 resolves platform and tenant to the same schema —
 * which is exactly why it is worth installing now. Phase 2 splits them, and by then the discipline is
 * in place and tested rather than being retrofitted against a red suite.
 *
 * <p><strong>A blanket pin is also how a suite goes green while the application is broken.</strong> For
 * most classes here it is honest: the subject runs inside a request, where {@code CurrentUserFilter}
 * pins for real. For a class whose subject IS the pinning — the router, the fail-closed layers, a job
 * that must declare its own axis — the harness pin makes the assertion unfalsifiable, and the fix is
 * {@link NoTenantAxis} on the class or {@link TenantAxis#withNoAxis} around the one call. Read
 * {@code NoTenantAxis}' javadoc before adding either; read it before deciding you do not need one.
 */
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(TenantAxisExtension.class)
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    // ECJ reports "Resource leak: '<unassigned Closeable value>' is never closed" on every container
    // field in the suite, and it is right that nothing calls close() — but closing would be the bug.
    // Testcontainers owns these: one static singleton is shared by every cached Spring context in the
    // run and torn down by the Ryuk reaper at JVM exit. try-with-resources, or any close() we added,
    // would stop the database out from under the tests still using it. Suppressed rather than
    // "fixed", here and at the other container fields, which point back to this comment.
    @SuppressWarnings("resource")
    public static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4-alpine")
                    // Headroom for the many cached Spring test contexts sharing this one container
                    // (each keeps a small Hikari pool — see application-test.yaml).
                    .withCommand("postgres", "-c", "max_connections=400");

    static {
        POSTGRES.start();
        // Boot's Flyway builds `platform` and nothing else since ADR 0010 Phase 4 retired the
        // V9999__tenant_pool.sql scaffold, so the tenant sequence has to be applied by the thing that
        // applies it in production — before any context here is built. See TenantSchemaBootstrap.
        TenantSchemaBootstrap.ensureMigrated(POSTGRES);
    }
}
