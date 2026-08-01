package ug.co.smsone.identity.internal;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

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

    /** The subject's federated identities (subject IS the Keycloak user id). Read-only by design. */
    List<Map<String, Object>> federatedIdentities(String subject) {
        try {
            List<?> identities = keycloakAdminRestClient.get()
                    .uri("/users/{id}/federated-identity", subject)
                    .retrieve()
                    .body(List.class);
            if (identities == null) {
                return List.of();
            }
            List<Map<String, Object>> result = new java.util.ArrayList<>();
            for (Object identity : identities) {
                if (identity instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> typed = (Map<String, Object>) map;
                    result.add(typed);
                }
            }
            return result;
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound notFound) {
            return List.of(); // unknown Keycloak user: no links, not an error surface
        }
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
        ResponseEntity<Void> response;
        try {
            response = keycloakAdminRestClient.post()
                    .uri("/users")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.Conflict ex) {
            // Two concurrent invites of the same new address both miss findByEmail and both POST;
            // Keycloak's unique username answers the loser with 409. Losing that race has a
            // well-defined idempotent outcome (AGENTS §4.4): the account exists — resolve to it.
            return findByEmail(email).orElseThrow(() -> new IllegalStateException(
                    "Keycloak reported a duplicate user for '" + email + "' but the lookup finds none"));
        }
        URI location = response.getHeaders().getLocation();
        if (location == null) {
            throw new IllegalStateException("Keycloak did not return a user id (no Location header)");
        }
        String path = location.getPath();
        return new KeycloakUser(path.substring(path.lastIndexOf('/') + 1), email);
    }

    /**
     * Grants a realm role. Two round-trips because the mapping endpoint wants the role's id, not its
     * name. Idempotent — Keycloak treats re-adding an existing mapping as a no-op — so a provisioning
     * retry is safe.
     *
     * <p>The service account needs BOTH {@code view-realm} (to read the role) and {@code manage-users}
     * (to write the mapping). Missing {@code view-realm} fails on the lookup with a 403 that looks like
     * a user-permission problem rather than a client-configuration one — see the realm export.
     */
    void assignRealmRole(String userId, String roleName) {
        Map<?, ?> role = keycloakAdminRestClient.get()
                .uri("/roles/{name}", roleName)
                .retrieve()
                .body(Map.class);
        if (role == null || role.get("id") == null) {
            throw new IllegalStateException("Keycloak realm role '" + roleName + "' does not exist");
        }
        keycloakAdminRestClient.post()
                .uri("/users/{id}/role-mappings/realm", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(List.of(Map.of("id", role.get("id"), "name", roleName)))
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * The <b>effective</b> realm roles a user holds — the set their token will actually carry.
     *
     * <p>{@code /role-mappings/realm/composite}, not {@code /role-mappings/realm}: the latter returns
     * only roles assigned straight to the account, while the token's {@code realm_access.roles} — the
     * claim {@link ug.co.smsone.shared.security.KeycloakJwtAuthenticationConverter} turns into
     * authorities — is the resolved set, with composite roles expanded and group-mapped roles included.
     * Reading the direct set would make every guard built on this method (impersonating a platform-role
     * holder) under-report exactly the tiering an ops team is most likely to set up: a composite
     * {@code ops-lead} role, or an {@code ops} group, carrying {@code platform-admin}.
     */
    Set<String> realmRoles(String userId) {
        List<?> roles = keycloakAdminRestClient.get()
                .uri("/users/{id}/role-mappings/realm/composite", userId)
                .retrieve()
                .body(List.class);
        if (roles == null) {
            return Set.of();
        }
        return roles.stream()
                .filter(Map.class::isInstance)
                .map(role -> String.valueOf(((Map<?, ?>) role).get("name")))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * Whether Keycloak still holds this account — deliberately THREE answers, not a boolean.
     *
     * <p>"Deleted" and "I could not find out" are different facts with opposite consequences, and a
     * boolean forces them into one bucket. The caller revokes access on {@code ABSENT}, so collapsing a
     * timeout, a 5xx, or a 403 from a rotated service-account secret into {@code false} would revoke
     * every user in the realm the first time Keycloak hiccups. Only a definitive 404 is {@code ABSENT};
     * everything else is {@code UNKNOWN} and must be left alone.
     */
    enum AccountPresence { PRESENT, ABSENT, UNKNOWN }

    AccountPresence accountPresence(String subject) {
        try {
            keycloakAdminRestClient.get().uri("/users/{id}", subject).retrieve().toBodilessEntity();
            return AccountPresence.PRESENT;
        } catch (HttpClientErrorException.NotFound ex) {
            return AccountPresence.ABSENT; // the only answer that is safe to act on
        } catch (RestClientException ex) {
            log.warn("Could not determine whether Keycloak still holds subject {}: {}", subject, ex.toString());
            return AccountPresence.UNKNOWN;
        }
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
