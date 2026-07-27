package ug.co.smsone.shared.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The Phase 1 security gate: a secured endpoint accepts a REAL JWT minted by a REAL Keycloak
 * (26.7.0 container importing the committed smsone realm) — validated via the JWKS the resource
 * server fetches from the container.
 */
@AutoConfigureMockMvc
class KeycloakIntegrationTest extends AbstractIntegrationTest {

    private static final int KEYCLOAK_PORT = 8080;

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
    static void keycloakProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> issuerUri());
    }

    private static String issuerUri() {
        return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(KEYCLOAK_PORT) + "/realms/smsone";
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void securedEndpointAcceptsRealKeycloakJwt() throws Exception {
        String token = passwordGrantToken("david", "david123");

        mockMvc.perform(get("/test/echo").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").value("hello"))
                .andExpect(jsonPath("$.meta.requestId").exists());
    }

    @Test
    void garbageTokenIsRejectedWithEnvelope() throws Exception {
        mockMvc.perform(get("/test/echo").header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errors[0].code").value("UNAUTHORIZED"));
    }

    private static String passwordGrantToken(String username, String password) throws Exception {
        String form = "grant_type=password&client_id=smsone-web"
                + "&username=" + username + "&password=" + password;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(issuerUri() + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            Matcher matcher = Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"").matcher(response.body());
            if (!matcher.find()) {
                throw new IllegalStateException("No access_token in token response: HTTP " + response.statusCode());
            }
            return matcher.group(1);
        }
    }
}
