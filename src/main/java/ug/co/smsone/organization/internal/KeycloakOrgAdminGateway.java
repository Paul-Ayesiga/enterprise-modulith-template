package ug.co.smsone.organization.internal;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ug.co.smsone.shared.error.ConflictException;

/**
 * Keycloak Admin REST calls for the Organizations API (create org, add a member). Rooted at
 * {@code /admin/realms/{realm}} by {@code keycloakAdminRestClient}; needs the service account to hold
 * {@code manage-organizations}.
 *
 * <p>Contract notes (Keycloak 26 Organizations, GA), pinned by the Testcontainers Keycloak IT:
 * <ul>
 *   <li>Create: {@code POST /organizations} with an OrganizationRepresentation; the new id comes back
 *       in the {@code Location} header. A domain is supplied (KC requires at least one); it is a
 *       placeholder — membership here is admin-managed, so the member's email need not match it.</li>
 *   <li>Add member: {@code POST /organizations/{orgId}/members} whose body is the user id as a bare
 *       JSON string ({@code "abc-123"}), matching the server's {@code addMember(String)} signature —
 *       sending {@code {"id":"…"}} is rejected as "User does not exist".</li>
 * </ul>
 */
@Component
class KeycloakOrgAdminGateway {

    private final RestClient keycloakAdminRestClient;

    KeycloakOrgAdminGateway(RestClient keycloakAdminRestClient) {
        this.keycloakAdminRestClient = keycloakAdminRestClient;
    }

    /** Creates the Keycloak organization and returns its id (the tenant key used everywhere locally). */
    UUID createOrganization(String alias, String name) {
        Map<String, Object> body = Map.of(
                "name", name,
                "alias", alias,
                "domains", List.of(Map.of("name", alias + ".smsone.local", "verified", false)));
        ResponseEntity<Void> response = keycloakAdminRestClient.post()
                .uri("/organizations")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                // Keycloak enforces alias/name uniqueness — its 409 closes the concurrent-create race
                // and must surface as the API's documented 409, not a 500.
                .onStatus(status -> status == HttpStatus.CONFLICT, (request, res) -> {
                    throw new ConflictException(
                            "An organization with alias '" + alias + "' (or this name) already exists.");
                })
                .toBodilessEntity();
        URI location = response.getHeaders().getLocation();
        if (location == null) {
            throw new IllegalStateException("Keycloak did not return an organization id (no Location header)");
        }
        String path = location.getPath();
        return UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
    }

    /** Looks up an existing Keycloak organization id by alias — lets the dev bootstrap re-adopt an org
     * that survived a local-DB reset instead of failing to re-create it. */
    Optional<UUID> findOrganizationIdByAlias(String alias) {
        List<?> results = keycloakAdminRestClient.get()
                .uri(uri -> uri.path("/organizations").queryParam("search", alias).queryParam("exact", true).build())
                .retrieve()
                .body(List.class);
        if (results == null) {
            return Optional.empty();
        }
        return results.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .filter(org -> alias.equals(String.valueOf(org.get("alias"))))
                .map(org -> UUID.fromString(String.valueOf(org.get("id"))))
                .findFirst();
    }

    /** Adds an existing Keycloak user to the organization. Idempotent server-side (re-add is a no-op 2xx). */
    void addMember(UUID kcOrgId, String userId) {
        keycloakAdminRestClient.post()
                .uri("/organizations/{orgId}/members", kcOrgId)
                .contentType(MediaType.APPLICATION_JSON)
                // Bare JSON string body — the endpoint consumes String, not a representation.
                .body("\"" + userId + "\"")
                .retrieve()
                .toBodilessEntity();
    }

    /** Removes a user from the organization. Never deletes the Keycloak user account itself. */
    void removeMember(UUID kcOrgId, String userId) {
        keycloakAdminRestClient.delete()
                .uri("/organizations/{orgId}/members/{userId}", kcOrgId, userId)
                .retrieve()
                .toBodilessEntity();
    }
}
