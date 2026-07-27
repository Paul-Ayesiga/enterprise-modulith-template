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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
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
import ug.co.smsone.settings.FeatureFlagChanged;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * Phase 2 gate for the notification module: a feature-flag toggle notifies admins over email (real
 * Mailpit) and in-app (persisted), and the pluggable dispatcher fans out across channels — proven
 * with a real webhook POST to an in-JVM HTTP server. No mocks; real Postgres + real Mailpit.
 */
@ApplicationModuleTest(mode = ApplicationModuleTest.BootstrapMode.ALL_DEPENDENCIES)
@ActiveProfiles("test")
class NotificationModuleTest {

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
    private JdbcTemplate jdbc;

    @Test
    void toggleNotifiesAdminsByEmailAndInApp(Scenario scenario) {
        scenario.publish(new FeatureFlagChanged("new-billing", true))
                .andWaitForStateChange(() -> inAppCountFor(ADMIN_USER) > 0 ? Boolean.TRUE : null)
                .andVerify(ready -> {
                    // Email delivered to the real Mailpit
                    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                        String messages = mailpitMessages();
                        assertThat(messages).contains(ADMIN_EMAIL).contains("new-billing").contains("enabled");
                    });
                    // In-app notification persisted for the admin
                    assertThat(inAppCountFor(ADMIN_USER)).isGreaterThanOrEqualTo(1);
                });
    }

    @Test
    void dispatchFansOutAcrossChannelsIncludingWebhook() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> webhookBody = new AtomicReference<>();
        server.createContext("/hook", exchange -> {
            webhookBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        String hookUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
        try {
            notifications.dispatch(new NotificationRequest(
                    "Deploy complete",
                    "Release v1.2.3 is live.",
                    List.of(Recipient.webhook(hookUrl),
                            Recipient.inApp("jane"),
                            Recipient.email("jane@smsone.co.ug")),
                    Map.of()));

            // Webhook received the real POST (synchronous channel)
            assertThat(webhookBody.get()).contains("Deploy complete").contains("v1.2.3");
            // In-app persisted for jane
            assertThat(inAppCountFor("jane")).isGreaterThanOrEqualTo(1);
            // Email reached Mailpit
            await().atMost(Duration.ofSeconds(10)).untilAsserted(
                    () -> assertThat(mailpitMessages()).contains("jane@smsone.co.ug").contains("Deploy complete"));
        } finally {
            server.stop(0);
        }
    }

    private long inAppCountFor(String recipient) {
        Long count = jdbc.queryForObject(
                "select count(*) from in_app_notification where recipient = ?", Long.class, recipient);
        return count == null ? 0 : count;
    }

    private String mailpitMessages() {
        return httpGet("http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(MAILPIT_HTTP) + "/api/v1/messages");
    }

    private static String httpGet(String url) {
        try {
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                            HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (Exception ex) {
            throw new IllegalStateException("GET " + url + " failed", ex);
        }
    }
}
