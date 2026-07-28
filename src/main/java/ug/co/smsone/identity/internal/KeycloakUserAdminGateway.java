package ug.co.smsone.identity.internal;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Keycloak Admin REST calls for the user lifecycle (find/create user, issue temporary credentials). */
@Component
class KeycloakUserAdminGateway {

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

    /** True when the account has at least one stored credential (e.g. a password). */
    boolean hasCredentials(String userId) {
        List<?> credentials = keycloakAdminRestClient.get()
                .uri("/users/{id}/credentials", userId)
                .retrieve()
                .body(List.class);
        return credentials != null && !credentials.isEmpty();
    }

    /**
     * Invite via Keycloak's {@code execute-actions-email}: the user receives an action link to set
     * their password (the admin never sees a credential). This is the ONLY credential mode — a
     * server-generated temporary password had no delivery channel, which stranded accounts.
     */
    void issueTemporaryCredentials(String userId) {
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
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
