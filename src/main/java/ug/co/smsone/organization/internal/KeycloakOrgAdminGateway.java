package ug.co.smsone.organization.internal;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ug.co.smsone.shared.error.ConflictException;

/**
 * Keycloak Admin REST calls for the Organizations API — creating the provider-side organization and
 * finding one by alias. Rooted at {@code /admin/realms/{realm}} by {@code keycloakAdminRestClient};
 * needs the service account to hold {@code manage-organizations}.
 *
 * <p><b>Member add/remove used to live here and no longer does.</b> Those endpoints take a Keycloak
 * USER id, and nothing below the edge may see one: this module knows people as {@code person.id}. They
 * moved behind {@code identity.ProviderOrgMembership}, implemented by the module that owns
 * {@code external_identity} and is therefore the only one that can translate a person into a subject —
 * the same discipline that keeps the org's provider id inside {@link ExternalOrganization}.
 *
 * <p>Contract notes (Keycloak 26 Organizations, GA), pinned by the Testcontainers Keycloak IT:
 * <ul>
 *   <li>Create: {@code POST /organizations} with an OrganizationRepresentation; the new id comes back
 *       in the {@code Location} header. A domain is supplied (KC requires at least one); it is a
 *       placeholder — membership here is admin-managed, so the member's email need not match it.</li>
 * </ul>
 */
@Component
class KeycloakOrgAdminGateway {

    private final RestClient keycloakAdminRestClient;

    KeycloakOrgAdminGateway(RestClient keycloakAdminRestClient) {
        this.keycloakAdminRestClient = keycloakAdminRestClient;
    }

    /**
     * Creates the Keycloak organization and returns its id — as a String, deliberately. It is
     * UUID-shaped because Keycloak mints UUIDs, and parsing it here would put that assumption back in
     * the type system on the way to {@code external_organization.external_org_id}, which is varchar for
     * precisely this reason (V11). To us it is an opaque provider identifier.
     */
    String createOrganization(String alias, String name) {
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
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /** Looks up an existing Keycloak organization id by alias — lets the dev bootstrap re-adopt an org
     * that survived a local-DB reset instead of failing to re-create it. Keycloak's {@code search}
     * matches an org's name/domain (not its alias), and {@code exact=true} requires an exact name/domain
     * hit — so it is a substring search here (our domain embeds the alias), pinned exact by the
     * {@code alias.equals} filter below. Verified against a live realm by the Keycloak org Admin-API IT. */
    Optional<String> findOrganizationIdByAlias(String alias) {
        List<?> results = keycloakAdminRestClient.get()
                .uri(uri -> uri.path("/organizations").queryParam("search", alias).build())
                .retrieve()
                .body(List.class);
        if (results == null) {
            return Optional.empty();
        }
        return results.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .filter(org -> alias.equals(String.valueOf(org.get("alias"))))
                .map(org -> String.valueOf(org.get("id")))
                .findFirst();
    }
}
