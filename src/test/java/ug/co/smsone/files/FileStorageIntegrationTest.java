package ug.co.smsone.files;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * Phase 2 gate: put/get/delete/presign/multipart against REAL SeaweedFS 4.40 (never trust S3
 * parity). Presigned URLs are exercised with a plain HTTP client — no SDK on the request path.
 */
class FileStorageIntegrationTest extends AbstractIntegrationTest {

    private static final int S3_PORT = 8333;

    @SuppressWarnings("resource") // Testcontainers owns this lifecycle — see AbstractIntegrationTest.POSTGRES
    private static final GenericContainer<?> SEAWEEDFS =
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
    static void storageProperties(DynamicPropertyRegistry registry) {
        registry.add("app.storage.endpoint",
                () -> "http://" + SEAWEEDFS.getHost() + ":" + SEAWEEDFS.getMappedPort(S3_PORT));
        registry.add("app.storage.bootstrap-bucket", () -> "true");
    }

    @Autowired
    private FileStorageProvider storage;

    @Test
    void putGetDeleteRoundtrip() throws Exception {
        byte[] payload = "hello seaweed".getBytes();
        storage.put("smoke/hello.txt", new ByteArrayInputStream(payload), payload.length, "text/plain");

        assertThat(storage.exists("smoke/hello.txt")).isTrue();
        assertThat(storage.get("smoke/hello.txt").readAllBytes()).isEqualTo(payload);

        storage.delete("smoke/hello.txt");
        assertThat(storage.exists("smoke/hello.txt")).isFalse();
    }

    @Test
    void presignedGetAndPutWorkWithoutSdk() throws Exception {
        byte[] payload = "presigned content".getBytes();
        storage.put("smoke/presign.txt", new ByteArrayInputStream(payload), payload.length, "text/plain");

        try (HttpClient http = HttpClient.newHttpClient()) {
            URL getUrl = storage.presignGet("smoke/presign.txt", Duration.ofMinutes(5));
            HttpResponse<byte[]> got = http.send(
                    HttpRequest.newBuilder().uri(getUrl.toURI()).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(got.statusCode()).isEqualTo(200);
            assertThat(got.body()).isEqualTo(payload);

            URL putUrl = storage.presignPut("smoke/presign-up.bin", "application/octet-stream",
                    Duration.ofMinutes(5));
            HttpResponse<String> uploaded = http.send(
                    HttpRequest.newBuilder().uri(putUrl.toURI())
                            .header("Content-Type", "application/octet-stream")
                            .PUT(HttpRequest.BodyPublishers.ofByteArray("client upload".getBytes()))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(uploaded.statusCode()).isEqualTo(200);
            assertThat(storage.get("smoke/presign-up.bin").readAllBytes())
                    .isEqualTo("client upload".getBytes());
        }
    }

    @Test
    void multipartUploadSurvivesRealSeaweedFs() throws Exception {
        byte[] big = new byte[11 * 1024 * 1024]; // 2 full 5MiB parts + remainder
        new Random(42).nextBytes(big);

        storage.putLarge("smoke/big.bin", new ByteArrayInputStream(big), big.length,
                "application/octet-stream");

        byte[] fetched = storage.get("smoke/big.bin").readAllBytes();
        assertThat(fetched).hasSameSizeAs(big);
        assertThat(fetched).isEqualTo(big);
    }
}
