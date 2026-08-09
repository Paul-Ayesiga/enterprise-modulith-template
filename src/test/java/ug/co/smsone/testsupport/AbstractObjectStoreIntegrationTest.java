package ug.co.smsone.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * {@link AbstractIntegrationTest} plus a REAL SeaweedFS behind the files port (ADR 0003 — never a
 * mocked S3 client, because S3 parity is exactly what a fake cannot test and this codebase has already
 * been bitten by it: multipart minimum part sizes, {@code isTruncated} semantics and presigned-URL
 * signing are all store behaviour, not SDK behaviour).
 *
 * <p><b>Why the container and the property source live in a shared base rather than in each test.</b>
 * Two things, and the second one is the expensive one. The container is a JVM singleton either way, but
 * a {@code @DynamicPropertySource} declared on a test CLASS makes that class's context configuration
 * unique — Spring's context cache keys on the customizer, which keys on the declaring methods — so two
 * classes each declaring their own would build and cache two full application contexts against the same
 * database and the same bucket. Declared once here and inherited, every object-store test shares one
 * context and one container.
 *
 * <p>The bucket is shared across every test in the run and nothing sweeps it, which is deliberate:
 * object keys in this codebase are namespaced by owner (ADR 0010 §6 item 7,
 * {@code shared.document.OrgObjectPrefixes}), so a test that generates its own organization or person id
 * cannot collide with another's. A test that needs an empty listing should list under an id it just
 * minted, never assume an empty bucket.
 */
public abstract class AbstractObjectStoreIntegrationTest extends AbstractIntegrationTest {

    private static final int S3_PORT = 8333;

    @SuppressWarnings("resource") // Testcontainers owns this lifecycle — see AbstractIntegrationTest.POSTGRES
    public static final GenericContainer<?> SEAWEEDFS =
            new GenericContainer<>("chrislusf/seaweedfs:4.40")
                    .withCommand("server", "-s3", "-s3.config=/etc/seaweedfs/s3-config.json")
                    .withCopyFileToContainer(
                            MountableFile.forHostPath("docker/seaweedfs/s3-config.json"),
                            "/etc/seaweedfs/s3-config.json")
                    .withExposedPorts(S3_PORT)
                    // anonymous S3 root returns 403 once the gateway is up (TC2 ignores
                    // forStatusCodeMatching here, so list the codes explicitly)
                    .waitingFor(Wait.forHttp("/").forPort(S3_PORT)
                            .forStatusCode(403)
                            .forStatusCode(200));

    static {
        SEAWEEDFS.start();
    }

    @DynamicPropertySource
    static void objectStoreProperties(DynamicPropertyRegistry registry) {
        registry.add("app.storage.endpoint",
                () -> "http://" + SEAWEEDFS.getHost() + ":" + SEAWEEDFS.getMappedPort(S3_PORT));
        registry.add("app.storage.bootstrap-bucket", () -> "true");
    }
}
