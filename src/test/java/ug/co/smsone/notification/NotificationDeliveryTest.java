package ug.co.smsone.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import ug.co.smsone.notification.internal.NotificationDeliveryWorker;
import ug.co.smsone.settings.FeatureFlagChanged;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * Durable-delivery-queue gate: dispatch enqueues (non-blocking), then the worker fans out
 * asynchronously to real Mailpit (email), the DB (in-app) and real webhook receivers — proving
 * bounded-concurrency fan-out to hundreds with no duplicate sends. The background poller is off in
 * tests ({@code worker-auto-start=false}); {@link NotificationDeliveryWorker#drainOnce()} is driven
 * explicitly for determinism.
 */
@ApplicationModuleTest(mode = ApplicationModuleTest.BootstrapMode.ALL_DEPENDENCIES)
@ActiveProfiles("test")
class NotificationDeliveryTest {

    private static final int MAILPIT_SMTP = 1025;
    private static final int MAILPIT_HTTP = 8025;
    private static final String ADMIN_EMAIL = "ops@smsone.co.ug";
    private static final String ADMIN_USER = "david";

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = AbstractIntegrationTest.POSTGRES;

    static final GenericContainer<?> MAILPIT =
            new GenericContainer<>("axllent/mailpit:v1.30.2")
                    .withExposedPorts(MAILPIT_SMTP, MAILPIT_HTTP)
                    .waitingFor(Wait.forHttp("/api/v1/info").forPort(MAILPIT_HTTP).forStatusCode(200));

    static {
        MAILPIT.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", MAILPIT::getHost);
        registry.add("spring.mail.port", () -> MAILPIT.getMappedPort(MAILPIT_SMTP));
        registry.add("app.notification.admins[0].username", () -> ADMIN_USER);
        registry.add("app.notification.admins[0].email", () -> ADMIN_EMAIL);
    }

    @Autowired
    private Notifications notifications;

    @Autowired
    private NotificationDeliveryWorker worker;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void flagToggleEnqueuesThenWorkerDeliversEmailAndInApp(Scenario scenario) throws Exception {
        scenario.publish(new FeatureFlagChanged("new-billing", true, Instant.now()))
                // listener enqueues synchronously (email + in-app); wait for the two queued rows
                .andWaitForStateChange(() -> queuedLike("new-billing") >= 2 ? Boolean.TRUE : null)
                .andVerify(ready -> assertThat(queuedLike("new-billing")).isGreaterThanOrEqualTo(2));

        drainFully();

        assertThat(inAppFor(ADMIN_USER, "new-billing")).isGreaterThanOrEqualTo(1);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(mailpitMessages()).contains(ADMIN_EMAIL).contains("new-billing").contains("enabled"));
    }

    @Test
    void dispatchFansOutAcrossChannels() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        StringBuilder webhookBody = new StringBuilder();
        server.createContext("/hook", exchange -> {
            synchronized (webhookBody) {
                webhookBody.append(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        String hookUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
        try {
            notifications.dispatch(new NotificationRequest("Deploy complete", "Release v1.2.3 is live.",
                    List.of(Recipient.webhook(hookUrl), Recipient.inApp("jane"), Recipient.email("jane@smsone.co.ug")),
                    Map.of()));
            drainFully();

            synchronized (webhookBody) {
                assertThat(webhookBody.toString()).contains("Deploy complete").contains("v1.2.3");
            }
            assertThat(inAppFor("jane", "Deploy complete")).isGreaterThanOrEqualTo(1);
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(mailpitMessages()).contains("jane@smsone.co.ug").contains("Deploy complete"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fansOutHundredsConcurrentlyWithoutDuplicates() throws Exception {
        int n = 300;
        String subject = "Bulk-" + UUID.randomUUID();
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.createContext("/bulk", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/bulk";
        try {
            List<Recipient> recipients = IntStream.range(0, n)
                    .mapToObj(i -> Recipient.webhook(base + "?i=" + i))
                    .toList();
            notifications.dispatch(new NotificationRequest(subject, "payload", recipients, Map.of()));
            drainFully();

            // Every recipient hit exactly once (unique URLs) => fan-out worked, no double-sends.
            assertThat(hits.get()).isEqualTo(n);
            Long sent = jdbc.queryForObject(
                    "select count(*) from notification_delivery where subject = ? and status = 'SENT'",
                    Long.class, subject);
            assertThat(sent).isEqualTo((long) n);
        } finally {
            server.stop(0);
        }
    }

    // ---- helpers ----

    private void drainFully() throws InterruptedException {
        while (worker.drainOnce() > 0) {
            // keep draining until the queue is empty
        }
    }

    private long queuedLike(String subjectFragment) {
        return count("select count(*) from notification_delivery where subject like ?", "%" + subjectFragment + "%");
    }

    private long inAppFor(String recipient, String subjectFragment) {
        return count("select count(*) from in_app_notification where recipient = ? and subject like ?",
                recipient, "%" + subjectFragment + "%");
    }

    private long count(String sql, Object... args) {
        Long c = jdbc.queryForObject(sql, Long.class, args);
        return c == null ? 0 : c;
    }

    private String mailpitMessages() {
        return httpGet("http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(MAILPIT_HTTP) + "/api/v1/messages");
    }

    private static String httpGet(String url) {
        try {
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (Exception ex) {
            throw new IllegalStateException("GET " + url + " failed", ex);
        }
    }
}
