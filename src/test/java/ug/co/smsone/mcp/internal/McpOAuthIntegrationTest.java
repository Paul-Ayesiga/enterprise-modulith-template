package ug.co.smsone.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * Phase 7's door policy against a REAL Keycloak importing the committed realm: a token minted with
 * the {@code mcp} scope carries both audiences and enters; the SAME user's token without the scope
 * (the web-app shape) is refused — no cross-surface token reuse. Also pins that anonymous dynamic
 * client registration is POLICED, not open: from this test runner the registration arrives via the
 * container bridge, an untrusted host under the realm's trusted-hosts policy, so it must refuse
 * (real Desktop connectors register from localhost, which the policy allows).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpOAuthIntegrationTest extends AbstractIntegrationTest {

    private static final int KEYCLOAK_PORT = 8080;

    /** Paul's durable subject in the committed realm export — imports preserve ids. */
    private static final String PAUL_SUB = "f48c20a4-89f9-4d7d-9fb4-dcf41fab7f44";

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
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> keycloakBase() + "/realms/smsone");
    }

    @LocalServerPort
    private int port;

    private static String keycloakBase() {
        return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(KEYCLOAK_PORT);
    }

    /** Password-grant a token for the committed dev user through smsone-web with the given scopes. */
    private static String token(String scope) throws Exception {
        String form = "grant_type=password&client_id=smsone-web"
                + "&username=paul&password=Paul123"
                + "&scope=" + URLEncoder.encode(scope, StandardCharsets.UTF_8);
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                                keycloakBase() + "/realms/smsone/protocol/openid-connect/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("token endpoint: %s", response.body()).isEqualTo(200);
        return JsonPath.read(response.body(), "$.access_token");
    }

    @Test
    void aConsentedMcpTokenEntersAndTheSameUsersWebTokenDoesNot() throws Exception {
        String mcpToken = token("openid mcp");

        // The mcp scope stamps BOTH audiences: smsone-api (the resource server's global check)
        // and smsone-mcp (the /mcp door policy).
        String claims = new String(Base64.getUrlDecoder().decode(mcpToken.split("\\.")[1]),
                StandardCharsets.UTF_8);
        assertThat(claims).contains("smsone-mcp").contains("smsone-api");

        try (McpSyncClient client = McpTestSupport.client(port,
                Map.of("Authorization", "Bearer " + mcpToken))) {
            assertThat(client.initialize().serverInfo().name()).isEqualTo("smsone-platform");
            McpSchema.CallToolResult result = client.callTool(
                    McpSchema.CallToolRequest.builder("whoami").build());
            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            @SuppressWarnings("unchecked")
            Map<String, Object> who = (Map<String, Object>) result.structuredContent();
            assertThat(who).containsEntry("authKind", "oauth").containsEntry("subject", PAUL_SUB);
        }

        // Same user, same realm, NO mcp scope — the web-app token shape. Refused at the door
        // even though it is a perfectly valid platform JWT.
        String webToken = token("openid");
        try (McpSyncClient client = McpTestSupport.client(port,
                Map.of("Authorization", "Bearer " + webToken))) {
            assertThatThrownBy(client::initialize).isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void anonymousDynamicClientRegistrationIsPolicedNotOpen() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                                keycloakBase() + "/realms/smsone/clients-registrations/openid-connect"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"client_name\":\"probe\",\"redirect_uris\":[\"http://localhost:33418/callback\"]}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        // The bridge address this arrives from is not a trusted host — the policy refuses.
        assertThat(response.statusCode()).as("registration answered: %s", response.body())
                .isIn(400, 401, 403);
    }
}
