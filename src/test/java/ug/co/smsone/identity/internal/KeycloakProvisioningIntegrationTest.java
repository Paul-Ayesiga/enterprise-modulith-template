package ug.co.smsone.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
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
import ug.co.smsone.identity.ProvisionedPerson;
import ug.co.smsone.identity.ProvisioningStatus;
import ug.co.smsone.identity.PersonProvisioning;
import ug.co.smsone.shared.security.PlatformRole;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * Pins the identity provisioning wire end-to-end against a REAL Keycloak (26.7.0 importing the
 * committed smsone realm) with a REAL SMTP sink (Mailpit). Verifies the audit's execute-actions-email
 * invite path that {@link IdentityProvisioningTest} only exercises with the gateway mocked: provisioning
 * a fresh user creates the Keycloak account, records the local {@code person} as INVITED, and the
 * invite e-mail actually reaches the mailbox — proving the create-user, credentials-check and
 * execute-actions-email calls and the realm SMTP config all line up. Keycloak reaches Mailpit over a
 * shared Docker network by the alias the committed realm points at ({@code mailpit:1025}).
 */
class KeycloakProvisioningIntegrationTest extends AbstractIntegrationTest {

    private static final int KEYCLOAK_PORT = 8080;
    private static final int MAILPIT_SMTP = 1025;
    private static final int MAILPIT_HTTP = 8025;

    private static final Network NETWORK = Network.newNetwork();

    @SuppressWarnings("resource") // Testcontainers owns this lifecycle — see AbstractIntegrationTest.POSTGRES
    private static final GenericContainer<?> MAILPIT =
            new GenericContainer<>("axllent/mailpit:v1.30.2")
                    .withNetwork(NETWORK)
                    .withNetworkAliases("mailpit") // the host the committed realm's smtpServer points at
                    .withExposedPorts(MAILPIT_SMTP, MAILPIT_HTTP)
                    .waitingFor(Wait.forHttp("/api/v1/info").forPort(MAILPIT_HTTP).forStatusCode(200));

    @SuppressWarnings("resource") // Testcontainers owns this lifecycle — see AbstractIntegrationTest.POSTGRES
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
    private PersonProvisioning provisioning;

    @Autowired
    private PersonRepository persons;

    @Autowired
    private PersonResolver resolver;

    @Autowired
    private KeycloakUserAdminGateway keycloak;

    @Test
    void provisioningANewPersonInvitesThemAndTheEmailReachesTheMailbox() {
        String email = "invitee-" + UUID.randomUUID() + "@smsone.co.ug";

        ProvisionedPerson person = provisioning.provision(new ProvisionRequest(email, "In", "Vitee"));

        assertThat(person.alreadyExisted()).isFalse();
        assertThat(persons.findById(person.personId())).get()
                .extracting(Person::getStatus).isEqualTo(ProvisioningStatus.INVITED);
        // The invite is an action e-mail (set-password link), not a stored credential.
        assertThat(keycloak.hasCredentials(subjectOf(person))).isFalse();

        // The real execute-actions-email send lands in Mailpit (Keycloak -> mailpit:1025 over the network).
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(mailpitMessages()).contains(email));

        // Re-provisioning finds the LINK (no second person, no re-create) — the link is the marker now.
        ProvisionedPerson again = provisioning.provision(new ProvisionRequest(email, "In", "Vitee"));
        assertThat(again.alreadyExisted()).isTrue();
        assertThat(again.personId()).isEqualTo(person.personId());
    }

    /**
     * Provisioning grants the baseline realm role and nothing above it. The negative half is the point:
     * invite is reachable by any org member holding {@code member:invite}, so if this path could attach
     * a platform role, a tenant could mint platform operators.
     */
    @Test
    void provisioningGrantsTheBaselineRealmRoleAndNoPlatformAuthority() {
        String email = "baseline-" + UUID.randomUUID() + "@smsone.co.ug";

        ProvisionedPerson person = provisioning.provision(new ProvisionRequest(email, "Base", "Line"));

        assertThat(keycloak.realmRoles(subjectOf(person)))
                .contains("USER")
                .noneMatch(PlatformRole::isPlatformRole);
    }

    /**
     * The tier guard for impersonation asks this gateway which platform roles a target holds, and its
     * answer has to be the set the target's TOKEN will carry — {@code realm_access.roles}, which Keycloak
     * resolves. Composite roles are the ordinary way an ops team tiers itself ({@code ops-lead} composed
     * of {@code platform-admin}), and the direct role-mapping endpoint does not expand them: reading it
     * would report "holds no platform role" about someone every {@code hasRole('platform-admin')} check
     * lets through, which is the guardrail failing silently in the one direction that matters.
     */
    @Test
    void realmRolesReportsRolesHeldThroughACompositeNotJustDirectMappings() {
        String email = "composite-" + UUID.randomUUID() + "@smsone.co.ug";
        ProvisionedPerson person = provisioning.provision(new ProvisionRequest(email, "Comp", "Osite"));
        String opsLead = "ops-lead-" + UUID.randomUUID();

        createComposite(opsLead, PlatformRole.ADMIN);
        keycloak.assignRealmRole(subjectOf(person), opsLead); // the ONLY direct mapping is the wrapper role

        assertThat(keycloak.realmRoles(subjectOf(person)))
                .contains(opsLead)
                .anyMatch(PlatformRole::isPlatformRole);
    }

    /**
     * The Keycloak subject behind a person. It is no longer part of the port's answer — that returns a
     * {@code person.id} — so a test that needs to talk to Keycloak about them resolves it back through
     * the one class allowed to, which is also the assertion that the link was written at all.
     */
    private String subjectOf(ProvisionedPerson person) {
        return resolver.keycloakSubjectOf(person.personId()).orElseThrow();
    }

    /**
     * The fixture roles are created with the realm's BOOTSTRAP ADMIN, not the app's service account.
     * That account holds only {@code view-realm} + {@code manage-users}, and it must stay that way: an
     * application able to mint realm roles could grant itself the platform tier it is supposed to be
     * constrained by. Composing roles is an operator action, so the test performs it as an operator.
     */
    private void createComposite(String parent, String child) {
        String token = bootstrapAdminToken();
        adminPost("/roles", "{\"name\":\"" + parent + "\"}", token);
        String childId = JsonPath.read(adminGet("/roles/" + child, token), "$.id");
        adminPost("/roles/" + parent + "/composites",
                "[{\"id\":\"" + childId + "\",\"name\":\"" + child + "\"}]", token);
    }

    private String bootstrapAdminToken() {
        String body = send(HttpRequest.newBuilder(URI.create(keycloakUrl("/realms/master/protocol/openid-connect/token")))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "grant_type=password&client_id=admin-cli&username=admin&password=admin")));
        return JsonPath.read(body, "$.access_token");
    }

    private String adminGet(String path, String token) {
        return send(HttpRequest.newBuilder(URI.create(keycloakUrl("/admin/realms/smsone" + path)))
                .header("Authorization", "Bearer " + token).GET());
    }

    private void adminPost(String path, String json, String token) {
        send(HttpRequest.newBuilder(URI.create(keycloakUrl("/admin/realms/smsone" + path)))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)));
    }

    private static String keycloakUrl(String path) {
        return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(KEYCLOAK_PORT) + path;
    }

    private static String send(HttpRequest.Builder request) {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("Keycloak admin call failed: " + response.statusCode()
                        + " " + response.body());
            }
            return response.body();
        } catch (Exception ex) {
            throw new IllegalStateException("Keycloak admin call failed", ex);
        }
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
