package ug.co.smsone.testsupport;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for all integration tests: full Spring context against a REAL Postgres via Testcontainers
 * (no H2, no fakes — template rule). Singleton container: started once for the whole JVM, shared by
 * every test class, reaped by Ryuk on exit.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18.4-alpine");

    static {
        POSTGRES.start();
    }
}
