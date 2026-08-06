package ug.co.smsone.testsupport;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base for all integration tests: full Spring context against a REAL Postgres via Testcontainers
 * (no H2, no fakes — template rule). Singleton container: started once for the whole JVM, shared by
 * every test class, reaped by Ryuk on exit.
 */
@SpringBootTest
@ActiveProfiles("test")
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
    }
}
