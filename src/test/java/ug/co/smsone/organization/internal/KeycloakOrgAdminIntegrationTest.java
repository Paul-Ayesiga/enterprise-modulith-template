package ug.co.smsone.organization.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;
import ug.co.smsone.identity.ProviderOrgMembership;
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
 * not through {@code PersonProvisioning}, which additionally sends an execute-actions-email that would
 * need realm SMTP.
 *
 * <p>The member half now drives {@link ProviderOrgMembership} rather than the gateway directly: those
 * two calls moved into {@code identity}, because they take a Keycloak USER id and only the module that
 * owns {@code external_identity} may hold one. The wire contract under test is unchanged — which is
 * exactly why the test moved with the calls instead of being deleted — but the port takes a
 * {@code person.id}, so the Keycloak user created here is linked to a real person first.
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

    @Autowired
    private ProviderOrgMembership providerOrgMembership;

    @Autowired
    private JdbcTemplate jdbc;

    /** The same property {@code PersonResolver} reads — external_identity.issuer must match it exactly. */
    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuer;

    @Test
    void createAddFindRemovePinTheKeycloakOrganizationsWireContract() {
        String alias = "acme-it-" + UUID.randomUUID().toString().substring(0, 8);

        // create-org: the new id comes back in the Location header, and the domains body is accepted.
        // It stays a String: it is UUID-shaped only because Keycloak mints UUIDs, and parsing it here
        // would put that assumption back into the type system (V11 — external_org_id is varchar).
        String orgId = orgGateway.createOrganization(alias, "Acme IT");
        assertThat(orgId).isNotNull();

        // search-by-alias round-trips to the same id (Keycloak's search matches name/domain, not alias,
        // so the gateway substring-searches and filters by alias — this pins that behaviour).
        assertThat(orgGateway.findOrganizationIdByAlias(alias)).contains(orgId);

        // A real Keycloak user to add, and the person this platform knows them as — the port takes the
        // person, and resolves the subject itself.
        String subject = createKeycloakUser(alias + "@smsone.co.ug");
        UUID personId = linkedPerson(subject);

        // attach: the server accepts the user id as a bare JSON string — the contract that was only
        // research-verified before.
        providerOrgMembership.attach(personId, orgId);
        assertThat(memberIds(orgId)).contains(subject);

        // Re-attach is an idempotent no-op: Keycloak 409s a re-add, and the port absorbs it.
        providerOrgMembership.attach(personId, orgId);
        assertThat(memberIds(orgId)).containsOnlyOnce(subject);

        // detach unlinks the membership; the user account itself remains.
        providerOrgMembership.detach(personId, orgId);
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

    /**
     * A person carrying this Keycloak subject — what {@code ProviderOrgMembership} translates back.
     *
     * <p>Seeded by hand rather than through {@code EdgeSeed}, and it has to stay that way: this class
     * repoints the issuer at the container above, so the link must be stamped with the injected
     * {@code issuer} and not the helper's fixed constant. {@code PersonResolver} looks up by
     * (provider, issuer, subject) — a link written under the wrong issuer resolves to nobody and the
     * attach below silently no-ops.
     */
    private UUID linkedPerson(String subject) {
        UUID personId = UUID.randomUUID();
        jdbc.update("""
                insert into person (id, status, invited_at, activated_at, version, created_at)
                values (?, 'ACTIVE', now(), now(), 0, now())
                """, personId);
        jdbc.update("""
                insert into external_identity (id, person_id, provider, issuer, external_subject,
                                               linked_at, version, created_at)
                values (?, ?, 'KEYCLOAK', ?, ?, now(), 0, now())
                """, UUID.randomUUID(), personId, issuer, subject);
        return personId;
    }

    private List<String> memberIds(String orgId) {
        List<Map<String, Object>> members = keycloakAdmin.get()
                .uri("/organizations/{id}/members", orgId)
                .retrieve()
                .body(new ParameterizedTypeReference<>() { });
        return members == null ? List.of() : members.stream().map(member -> String.valueOf(member.get("id"))).toList();
    }
}
