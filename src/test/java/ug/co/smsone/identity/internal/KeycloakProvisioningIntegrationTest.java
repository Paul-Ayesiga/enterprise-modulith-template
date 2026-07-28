package ug.co.smsone.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;
import ug.co.smsone.identity.ProvisionRequest;
import ug.co.smsone.identity.ProvisionedUser;
import ug.co.smsone.identity.ProvisioningStatus;
import ug.co.smsone.identity.UserProvisioning;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * Pins the identity provisioning wire end-to-end against a REAL Keycloak (26.7.0 importing the
 * committed smsone realm) with a REAL SMTP sink (Mailpit). Verifies the audit's execute-actions-email
 * invite path that {@link IdentityProvisioningTest} only exercises with the gateway mocked: provisioning
 * a fresh user creates the Keycloak account, records the local {@code app_user} as INVITED, and the
 * invite e-mail actually reaches the mailbox — proving the create-user, credentials-check and
 * execute-actions-email calls and the realm SMTP config all line up. Keycloak reaches Mailpit over a
 * shared Docker network by the alias the committed realm points at ({@code mailpit:1025}).
 */
class KeycloakProvisioningIntegrationTest extends AbstractIntegrationTest {

    private static final int KEYCLOAK_PORT = 8080;
    private static final int MAILPIT_SMTP = 1025;
    private static final int MAILPIT_HTTP = 8025;

    private static final Network NETWORK = Network.newNetwork();

    private static final GenericContainer<?> MAILPIT =
            new GenericContainer<>("axllent/mailpit:v1.30.2")
                    .withNetwork(NETWORK)
                    .withNetworkAliases("mailpit") // the host the committed realm's smtpServer points at
                    .withExposedPorts(MAILPIT_SMTP, MAILPIT_HTTP)
                    .waitingFor(Wait.forHttp("/api/v1/info").forPort(MAILPIT_HTTP).forStatusCode(200));

    private static final GenericContainer<?> KEYCLOAK =
            new GenericContainer<>("quay.io/keycloak/keycloak:26.7.0")
                    .withNetwork(NETWORK)
                    .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
                    .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
                    .withCommand("start-dev", "--import-realm")
                    .withCopyFileToContainer(
                            MountableFile.forHostPath("docker/keycloak/realm-smsone.json"),
                            "/opt/keycloak/data/import/realm-smsone.json")
                    .withExposedPorts(KEYCLOAK_PORT)
                    .waitingFor(Wait.forHttp("/realms/smsone/.well-known/openid-configuration")
                            .forPort(KEYCLOAK_PORT));

    static {
        MAILPIT.start();
        KEYCLOAK.start();
    }

    @DynamicPropertySource
    static void keycloak(DynamicPropertyRegistry registry) {
        String base = "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(KEYCLOAK_PORT);
        registry.add("app.keycloak-admin.base-url", () -> base);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> base + "/realms/smsone");
    }

    @Autowired
    private UserProvisioning provisioning;

    @Autowired
    private UserRepository users;

    @Autowired
    private KeycloakUserAdminGateway keycloak;

    @Test
    void provisioningANewUserInvitesThemAndTheEmailReachesTheMailbox() {
        String email = "invitee-" + UUID.randomUUID() + "@smsone.co.ug";

        ProvisionedUser user = provisioning.provision(new ProvisionRequest(email, "In", "Vitee"));

        assertThat(user.alreadyExisted()).isFalse();
        assertThat(users.findBySubject(user.subject())).get()
                .extracting(User::getStatus).isEqualTo(ProvisioningStatus.INVITED);
        // The invite is an action e-mail (set-password link), not a stored credential.
        assertThat(keycloak.hasCredentials(user.subject())).isFalse();

        // The real execute-actions-email send lands in Mailpit (Keycloak -> mailpit:1025 over the network).
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(mailpitMessages()).contains(email));

        // Re-provisioning finds the account (no second local row, no re-create).
        ProvisionedUser again = provisioning.provision(new ProvisionRequest(email, "In", "Vitee"));
        assertThat(again.alreadyExisted()).isTrue();
        assertThat(again.subject()).isEqualTo(user.subject());
    }

    private String mailpitMessages() {
        String url = "http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(MAILPIT_HTTP) + "/api/v1/messages";
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (Exception ex) {
            throw new IllegalStateException("GET " + url + " failed", ex);
        }
    }
}
