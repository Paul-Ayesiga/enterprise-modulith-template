package ug.co.smsone.organization.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * Pins the Keycloak 26 Organizations Admin-API wire contract against a REAL Keycloak (26.7.0 importing
 * the committed smsone realm) — the piece {@link KeycloakOrgAdminGateway} previously only assumed from
 * docs. Verifies: create-org returns the new id in the {@code Location} header (and the domains body is
 * accepted), search-by-alias round-trips, add-member accepts the user id as a bare JSON string, and
 * remove-member unlinks without deleting the user — AND that the imported {@code smsone-admin} service
 * account actually holds the realm-management roles the org Admin API requires.
 *
 * <p>Scope is the org wire; the member is created via the raw Admin API (same create-user contract),
 * not through {@code UserProvisioning}, which additionally sends an execute-actions-email that would
 * need realm SMTP.
 */
class KeycloakOrgAdminIntegrationTest extends AbstractIntegrationTest {

    private static final int KEYCLOAK_PORT = 8080;

    @SuppressWarnings("resource") // Testcontainers owns this lifecycle — see AbstractIntegrationTest.POSTGRES
    private static final GenericContainer<?> KEYCLOAK =
            new GenericContainer<>("quay.io/keycloak/keycloak:26.7.0")
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
        KEYCLOAK.start();
    }

    @DynamicPropertySource
    static void keycloak(DynamicPropertyRegistry registry) {
        String base = "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(KEYCLOAK_PORT);
        registry.add("app.keycloak-admin.base-url", () -> base);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> base + "/realms/smsone");
    }

    @Autowired
    private KeycloakOrgAdminGateway orgGateway;

    @Autowired
    @Qualifier("keycloakAdminRestClient")
    private RestClient keycloakAdmin;

    @Test
    void createAddFindRemovePinTheKeycloakOrganizationsWireContract() {
        String alias = "acme-it-" + UUID.randomUUID().toString().substring(0, 8);

        // create-org: the new id comes back in the Location header, and the domains body is accepted.
        UUID orgId = orgGateway.createOrganization(alias, "Acme IT");
        assertThat(orgId).isNotNull();

        // search-by-alias round-trips to the same id (Keycloak's search matches name/domain, not alias,
        // so the gateway substring-searches and filters by alias — this pins that behaviour).
        assertThat(orgGateway.findOrganizationIdByAlias(alias)).contains(orgId);

        // A real Keycloak user to add.
        String subject = createKeycloakUser(alias + "@smsone.co.ug");

        // add-member: the server accepts the user id as a bare JSON string — the contract that was only
        // research-verified before.
        orgGateway.addMember(orgId, subject);
        assertThat(memberIds(orgId)).contains(subject);

        // Re-add is an idempotent no-op 2xx.
        orgGateway.addMember(orgId, subject);
        assertThat(memberIds(orgId)).containsOnlyOnce(subject);

        // remove-member unlinks the membership; the user account itself remains.
        orgGateway.removeMember(orgId, subject);
        assertThat(memberIds(orgId)).doesNotContain(subject);
        assertThat(keycloakUserExists(subject)).isTrue();
    }

    private String createKeycloakUser(String email) {
        ResponseEntity<Void> response = keycloakAdmin.post()
                .uri("/users")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", email, "email", email, "enabled", true, "emailVerified", true))
                .retrieve()
                .toBodilessEntity();
        URI location = response.getHeaders().getLocation();
        String path = location.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private boolean keycloakUserExists(String userId) {
        return keycloakAdmin.get().uri("/users/{id}", userId)
                .exchange((request, res) -> res.getStatusCode().is2xxSuccessful());
    }

    private List<String> memberIds(UUID orgId) {
        List<Map<String, Object>> members = keycloakAdmin.get()
                .uri("/organizations/{id}/members", orgId)
                .retrieve()
                .body(new ParameterizedTypeReference<>() { });
        return members == null ? List.of() : members.stream().map(member -> String.valueOf(member.get("id"))).toList();
    }
}
