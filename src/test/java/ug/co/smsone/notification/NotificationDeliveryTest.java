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
import org.junit.jupiter.api.BeforeEach;
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
    private static final String ADMIN_SUBJECT = "kc-sub-admin-0001"; // app_user row seeded per test

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
        registry.add("app.notification.admins[0].email", () -> ADMIN_EMAIL);
        // Longer than any drainFully() loop: a failed delivery must stay parked until the test
        // explicitly fast-forwards next_attempt_at, making retry counts deterministic.
        registry.add("app.notification.delivery.retry-base-backoff", () -> "PT5S");
    }

    @Autowired
    private Notifications notifications;

    @Autowired
    private NotificationDeliveryWorker worker;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetQueue() {
        // Shared singleton Postgres: other classes (any feature-flag toggle fans admin rows into this
        // queue) leave rows behind, and the worker drains/claims globally. Start each test from empty.
        jdbc.update("delete from notification_delivery");
        jdbc.update("delete from in_app_notification");
    }

    @Test
    void flagToggleEnqueuesThenWorkerDeliversEmailAndInApp(Scenario scenario) throws Exception {
        seedProvisionedAdmin(); // in-app targeting resolves the admin's SUBJECT from this app_user row

        scenario.publish(new FeatureFlagChanged("new-billing", true, Instant.now()))
                // listener enqueues synchronously (email + in-app); wait for the two queued rows
                .andWaitForStateChange(() -> queuedLike("new-billing") >= 2 ? Boolean.TRUE : null)
                .andVerify(ready -> assertThat(queuedLike("new-billing")).isGreaterThanOrEqualTo(2));

        // Deliver, tolerating the async send pipeline: processBatch waits on a latch with a timeout, so
        // under load drainFully() can return with a send still in flight. Re-drain until the in-app row
        // (keyed by the immutable Keycloak subject, never the mutable username) has actually landed.
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            drainFully();
            assertThat(inAppFor(ADMIN_SUBJECT, "new-billing")).isGreaterThanOrEqualTo(1);
        });
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(mailpitMessages()).contains(ADMIN_EMAIL).contains("new-billing").contains("enabled"));
    }

    private void seedProvisionedAdmin() {
        jdbc.update("""
                insert into app_user (id, subject, email, status, provisioned_at, version, created_at)
                values (?, ?, ?, 'ACTIVE', now(), 0, now())
                on conflict (subject) do nothing
                """, UUID.randomUUID(), ADMIN_SUBJECT, ADMIN_EMAIL);
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
                    List.of(Recipient.webhook(hookUrl), Recipient.inApp("recipient-1"),
                            Recipient.email("recipient-1@smsone.co.ug")),
                    Map.of()));
            drainFully();

            synchronized (webhookBody) {
                assertThat(webhookBody.toString()).contains("Deploy complete").contains("v1.2.3");
            }
            assertThat(inAppFor("recipient-1", "Deploy complete")).isGreaterThanOrEqualTo(1);
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(mailpitMessages()).contains("recipient-1@smsone.co.ug").contains("Deploy complete"));
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

    @Test
    void permanent4xxIsDeadLetteredWithoutBurningRetries() throws Exception {
        String subject = "Gone-" + UUID.randomUUID();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/gone", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(410, -1); // receiver says: permanently gone
            exchange.close();
        });
        server.start();
        try {
            notifications.dispatch(new NotificationRequest(subject, "bye",
                    List.of(Recipient.webhook("http://127.0.0.1:" + server.getAddress().getPort() + "/gone")),
                    Map.of()));
            drainFully();

            // One attempt, then FAILED — a contract rejection is not retried on a schedule.
            assertThat(hits.get()).isEqualTo(1);
            assertThat(statusFor(subject)).isEqualTo("FAILED");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void transient5xxIsRetriedWithBackoffUntilDeadLettered() throws Exception {
        String subject = "Flaky-" + UUID.randomUUID();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/flaky", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(503, -1); // transient — retry with backoff
            exchange.close();
        });
        server.start();
        try {
            notifications.dispatch(new NotificationRequest(subject, "retry me",
                    List.of(Recipient.webhook("http://127.0.0.1:" + server.getAddress().getPort() + "/flaky")),
                    Map.of()));
            drainFully();
            assertThat(statusFor(subject)).isEqualTo("PENDING"); // rescheduled, not dead yet
            assertThat(hits.get()).isEqualTo(1);

            // Fast-forward the backoff instead of sleeping through it and drain, repeating until the
            // delivery is dead-lettered — tolerant of a drain that doesn't deliver under load (the next
            // poll fast-forwards and retries), instead of assuming exactly one delivery per iteration.
            int maxAttempts = 5; // app.notification.delivery.max-attempts default
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                jdbc.update("update notification_delivery set next_attempt_at = now() where subject = ?", subject);
                drainFully();
                assertThat(statusFor(subject)).isEqualTo("FAILED");
            });
            assertThat(hits.get()).isEqualTo(maxAttempts);
        } finally {
            server.stop(0);
        }
    }

    private String statusFor(String subject) {
        return jdbc.queryForObject(
                "select status from notification_delivery where subject = ?", String.class, subject);
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
