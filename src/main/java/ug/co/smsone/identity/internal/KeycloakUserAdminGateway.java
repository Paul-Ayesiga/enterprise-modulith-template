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

/**
 * Keycloak Admin REST calls for the account lifecycle (find/create user, issue temporary credentials).
 *
 * <p><b>This class is the one place Keycloak's vocabulary is allowed to exist.</b> Its API speaks
 * {@code firstName}/{@code lastName} and user ids because Keycloak does, and the translation to
 * {@code givenName}/{@code familyName} and {@code person.id} happens at this boundary — the same
 * discipline that keeps issuers and subjects inside {@link PersonResolver}. A caller of this class is
 * expected to be an adapter; a caller of a caller must never see either word.
 */
@Component
class KeycloakUserAdminGateway {

    private static final Logger log = LoggerFactory.getLogger(KeycloakUserAdminGateway.class);

    private final RestClient keycloakAdminRestClient;
    private final ProvisioningProperties properties;

    KeycloakUserAdminGateway(RestClient keycloakAdminRestClient, ProvisioningProperties properties) {
        this.keycloakAdminRestClient = keycloakAdminRestClient;
        this.properties = properties;
    }

    /** {@code id} is Keycloak's user id — the JWT {@code sub}. It is an EXTERNAL subject, never a person. */
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

    /**
     * Creates the Keycloak account for an address.
     *
     * <p>The parameters are {@code givenName}/{@code familyName} and the body writes {@code firstName}/
     * {@code lastName}: THIS LINE is the whole reason the pair is still spelled that way anywhere in the
     * codebase. Keycloak's user representation has those two field names and no others, so the mapping
     * has to happen somewhere; doing it here means it happens once, in the class whose job is to speak
     * Keycloak, rather than leaking a positional naming convention into a domain that has people with
     * one name, three names, and family names that come first.
     */
    KeycloakUser createUser(String email, String givenName, String familyName) {
        Map<String, Object> body = Map.<String, Object>of(
                "username", email,
                "email", email,
                "emailVerified", false,
                "enabled", true,
                "firstName", nullToEmpty(givenName),
                "lastName", nullToEmpty(familyName));
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
     * The subjects (Keycloak user ids) that hold a realm role, by its DIRECT assignment — the reverse of
     * {@link #realmRoles(String)}. Used to guard the last super-admin: a role granted only through a
     * composite or a group would not appear here, so the guard errs toward permitting (never falsely
     * blocking) an erasure.
     */
    Set<String> usersWithRealmRole(String roleName) {
        List<?> users = keycloakAdminRestClient.get()
                .uri("/roles/{name}/users", roleName)
                .retrieve()
                .body(List.class);
        if (users == null) {
            return Set.of();
        }
        return users.stream()
                .filter(Map.class::isInstance)
                .map(user -> String.valueOf(((Map<?, ?>) user).get("id")))
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
     * {@code actions} carries the required actions the link executes — always UPDATE_PASSWORD and
     * VERIFY_EMAIL, plus CONFIGURE_TOTP when the org's policy requires MFA.
     */
    void issueTemporaryCredentials(String userId, List<String> actions) {
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
                .body(actions)
                .retrieve()
                .toBodilessEntity();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
