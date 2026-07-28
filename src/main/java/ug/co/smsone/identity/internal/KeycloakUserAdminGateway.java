package ug.co.smsone.identity.internal;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ug.co.smsone.identity.internal.ProvisioningProperties.TempCredentialMode;

/** Keycloak Admin REST calls for the user lifecycle (find/create user, issue temporary credentials). */
@Component
class KeycloakUserAdminGateway {

    private static final Logger log = LoggerFactory.getLogger(KeycloakUserAdminGateway.class);

    private final RestClient keycloakAdminRestClient;
    private final ProvisioningProperties properties;

    KeycloakUserAdminGateway(RestClient keycloakAdminRestClient, ProvisioningProperties properties) {
        this.keycloakAdminRestClient = keycloakAdminRestClient;
        this.properties = properties;
    }

    record KeycloakUser(String id, String email) {
    }

    Optional<KeycloakUser> findByEmail(String email) {
        List<?> results = keycloakAdminRestClient.get()
                .uri(uri -> uri.path("/users").queryParam("email", email).queryParam("exact", true).build())
                .retrieve()
                .body(List.class);
        if (results == null || results.isEmpty() || !(results.get(0) instanceof Map<?, ?> user)) {
            return Optional.empty();
        }
        return Optional.of(new KeycloakUser(String.valueOf(user.get("id")), String.valueOf(user.get("email"))));
    }

    KeycloakUser createUser(String email, String firstName, String lastName) {
        Map<String, Object> body = Map.<String, Object>of(
                "username", email,
                "email", email,
                "emailVerified", false,
                "enabled", true,
                "firstName", nullToEmpty(firstName),
                "lastName", nullToEmpty(lastName));
        ResponseEntity<Void> response = keycloakAdminRestClient.post()
                .uri("/users")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
        URI location = response.getHeaders().getLocation();
        if (location == null) {
            throw new IllegalStateException("Keycloak did not return a user id (no Location header)");
        }
        String path = location.getPath();
        return new KeycloakUser(path.substring(path.lastIndexOf('/') + 1), email);
    }

    void issueTemporaryCredentials(String userId) {
        if (properties.tempCredentialMode() == TempCredentialMode.EMAIL) {
            keycloakAdminRestClient.put()
                    .uri(uri -> {
                        var builder = uri.path("/users/{id}/execute-actions-email")
                                .queryParam("client_id", properties.appClientId())
                                .queryParam("lifespan", properties.inviteLifespan().toSeconds());
                        if (properties.redirectUri() != null && !properties.redirectUri().isBlank()) {
                            builder.queryParam("redirect_uri", properties.redirectUri());
                        }
                        return builder.build(userId);
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(List.of("UPDATE_PASSWORD", "VERIFY_EMAIL"))
                    .retrieve()
                    .toBodilessEntity();
        } else {
            // TEMP_PASSWORD: single-use random password (forced change at first login); deliver out-of-band.
            String temporary = UUID.randomUUID() + "Aa1!";
            keycloakAdminRestClient.put()
                    .uri("/users/{id}/reset-password", userId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("type", "password", "value", temporary, "temporary", true))
                    .retrieve()
                    .toBodilessEntity();
            log.info("Temporary password set for Keycloak user {} — deliver out-of-band (mode=TEMP_PASSWORD)", userId);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
