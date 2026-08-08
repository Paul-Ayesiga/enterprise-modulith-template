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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ug.co.smsone.notification.internal.NotificationDeliveryWorker;
import ug.co.smsone.settings.FeatureFlagChanged;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.TenantAxis;
import ug.co.smsone.testsupport.TenantAxisExtension;

/**
 * Durable-delivery-queue gate: dispatch enqueues (non-blocking), then the worker fans out
 * asynchronously to real Mailpit (email), the DB (in-app) and real webhook receivers — proving
 * bounded-concurrency fan-out to hundreds with no duplicate sends. The background poller is off in
 * tests ({@code worker-auto-start=false}); {@link NotificationDeliveryWorker#drainOnce()} is driven
 * explicitly for determinism.
 *
 * <p>{@code @ExtendWith(TenantAxisExtension.class)} because this class bootstraps its own slice rather
 * than extending {@code AbstractIntegrationTest}, and so would otherwise run with no tenant axis
 * (ADR 0010 §3.4) — {@link #resetQueue()} truncates two tables before every test and is the first thing
 * that would fail.
 *
 * <p><strong>Every drive of the worker runs with the axis taken OFF.</strong> In production
 * {@code drainOnce()} runs on the worker's own pooled platform thread, where nothing has declared an
 * axis — so a worker that does not declare its own is broken there. Called plainly from a test method
 * it would borrow the harness's pin instead, pass, and prove nothing; {@link TenantAxis#withNoAxis} is
 * what puts the drive back in the state the poller hands it (ADR 0010 §3.4). Inside an
 * {@code await(…)} the drive is already unpinned — Awaitility polls on its own thread — and
 * {@link #count} and friends are what need the axis there instead.
 */
@ApplicationModuleTest(mode = ApplicationModuleTest.BootstrapMode.ALL_DEPENDENCIES)
@ActiveProfiles("test")
@ExtendWith(TenantAxisExtension.class)
class NotificationDeliveryTest {

    private static final int MAILPIT_SMTP = 1025;
    private static final int MAILPIT_HTTP = 8025;
    private static final String ADMIN_EMAIL = "ops@smsone.co.ug";
    private static final UUID ADMIN_PERSON_ID = UUID.fromString("00000000-0000-4000-8000-0000000ad301");

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = AbstractIntegrationTest.POSTGRES;

    @SuppressWarnings("resource") // Testcontainers owns this lifecycle — see AbstractIntegrationTest.POSTGRES
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
        seedProvisionedAdmin(); // in-app targeting resolves the admin's PERSON from these rows

        scenario.publish(new FeatureFlagChanged("new-billing", true, Instant.now()))
                // listener enqueues synchronously (email + in-app); wait for the two queued rows
                .andWaitForStateChange(() -> queuedLike("new-billing") >= 2 ? Boolean.TRUE : null)
                .andVerify(ready -> assertThat(queuedLike("new-billing")).isGreaterThanOrEqualTo(2));

        // Deliver, tolerating the async send pipeline: processBatch waits on a latch with a timeout, so
        // under load drainFully() can return with a send still in flight. Re-drain until the in-app row
        // (keyed by person.id, the platform's only durable answer to "who") has actually landed.
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            drainFullyUnpinned();
            assertThat(inAppFor(ADMIN_PERSON_ID, "new-billing")).isGreaterThanOrEqualTo(1);
        });
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(mailpitMessages()).contains(ADMIN_EMAIL).contains("new-billing").contains("enabled"));
    }

    /**
     * A person and the address to reach them at — two rows now, because V10 split the identity from the
     * contact. The e-mail lives in person_contact, which is what NotificationService resolves through.
     *
     * <p>Hand-written rather than {@code EdgeSeed.personWithEmail}: this person's id is a CONSTANT the
     * test also hands to {@code Recipient.inApp} and then queries {@code in_app_notification} by, and the
     * helper mints a fresh id (plus a Keycloak link nothing here authenticates through). The columns are
     * the current ones: {@code invited_at} is what {@code provisioned_at} became, and an ACTIVE person
     * carries {@code activated_at} too — a status the provisioning path never leaves unset.
     */
    private void seedProvisionedAdmin() {
        jdbc.update("""
                insert into person (id, status, invited_at, activated_at, version, created_at)
                values (?, 'ACTIVE', now(), now(), 0, now())
                on conflict (id) do nothing
                """, ADMIN_PERSON_ID);
        // The `where` is not optional: uq_person_contact_verified_live is PARTIAL, and Postgres only
        // infers a partial index as the ON CONFLICT arbiter when the statement repeats its predicate.
        jdbc.update("""
                insert into person_contact (id, person_id, kind, contact_value, is_primary, verified_at,
                                            version, created_at)
                values (?, ?, 'EMAIL', ?, true, now(), 0, now())
                on conflict (kind, lower(contact_value)) where verified_at is not null and deleted_at is null
                do nothing
                """, UUID.randomUUID(), ADMIN_PERSON_ID, ADMIN_EMAIL);
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
                    List.of(Recipient.webhook(hookUrl), Recipient.inApp(ADMIN_PERSON_ID),
                            Recipient.email("recipient-1@smsone.co.ug")),
                    Map.of()));
            drainFullyUnpinned();

            synchronized (webhookBody) {
                assertThat(webhookBody.toString()).contains("Deploy complete").contains("v1.2.3");
            }
            assertThat(inAppFor(ADMIN_PERSON_ID, "Deploy complete")).isGreaterThanOrEqualTo(1);
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
            drainFullyUnpinned();

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
            drainFullyUnpinned();

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
            drainFullyUnpinned();
            assertThat(statusFor(subject)).isEqualTo("PENDING"); // rescheduled, not dead yet
            assertThat(hits.get()).isEqualTo(1);

            // Fast-forward the backoff instead of sleeping through it and drain, repeating until the
            // delivery is dead-lettered — tolerant of a drain that doesn't deliver under load (the next
            // poll fast-forwards and retries), instead of assuming exactly one delivery per iteration.
            int maxAttempts = 5; // app.notification.delivery.max-attempts default
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                fastForwardBackoff(subject);
                drainFullyUnpinned();
                assertThat(statusFor(subject)).isEqualTo("FAILED");
            });
            assertThat(hits.get()).isEqualTo(maxAttempts);
        } finally {
            server.stop(0);
        }
    }

    private String statusFor(String subject) {
        return TenantContext.callAsPlatform(() -> jdbc.queryForObject(
                "select status from notification_delivery where subject = ?", String.class, subject));
    }

    /**
     * Brings a parked retry due now instead of sleeping out its backoff.
     *
     * <p>Pinned for the same reason {@link #count} is: its only caller is inside
     * {@code await().untilAsserted(…)}, and Awaitility's poll thread carries no tenant axis, so the
     * update would fail with {@code relation "notification_delivery" does not exist} rather than
     * moving the row (ADR 0010 §3.4).
     */
    private void fastForwardBackoff(String subject) {
        TenantContext.runAsPlatform(() -> jdbc.update(
                "update notification_delivery set next_attempt_at = now() where subject = ?", subject));
    }

    // ---- helpers ----

    /**
     * Drains with NO axis declared, which is the state the worker's own poller thread arrives in.
     * See the class note: pinned, this would prove nothing about
     * {@link NotificationDeliveryWorker#drainOnce()} declaring its own.
     */
    private void drainFullyUnpinned() throws Exception {
        TenantAxis.withNoAxis(this::drainFully);
    }

    private void drainFully() throws InterruptedException {
        while (worker.drainOnce() > 0) {
            // keep draining until the queue is empty
        }
    }

    private long queuedLike(String subjectFragment) {
        return count("select count(*) from notification_delivery where subject like ?", "%" + subjectFragment + "%");
    }

    private long inAppFor(UUID personId, String subjectFragment) {
        return count("select count(*) from in_app_notification where person_id = ? and subject like ?",
                personId, "%" + subjectFragment + "%");
    }

    /**
     * Every count this class makes, with the tenant axis declared on it.
     *
     * <p>Most callers sit inside {@code await(…)} or inside a Modulith {@code Scenario}'s state-change
     * poll — both of which run on Awaitility's own thread, which the harness never pins (ADR 0010
     * §3.4). Unpinned, the borrow routes to the empty {@code no_tenant} schema and the count fails
     * with {@code relation "notification_delivery" does not exist} instead of returning a number the
     * assertion can be wrong about. Declaring it here covers the callers on the test thread too, where
     * it is a harmless re-pin of the axis they already hold.
     */
    private long count(String sql, Object... args) {
        Long c = TenantContext.callAsPlatform(() -> jdbc.queryForObject(sql, Long.class, args));
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
