package ug.co.smsone.identity.internal;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ug.co.smsone.identity.ProviderOrgMembership;

/**
 * {@link ProviderOrgMembership} over Keycloak's Organizations API. Rooted at
 * {@code /admin/realms/{realm}} by {@code keycloakAdminRestClient}; needs the service account to hold
 * {@code manage-organizations}.
 *
 * <p>These two calls used to sit in {@code organization.internal}, next to the org-create call they
 * belong with functionally. They are here instead because they take a Keycloak user id, and resolving a
 * person into one over there would have meant this module handing out a subject — the one thing the
 * boundary exists to prevent. {@link PersonResolver} does the translation and it never leaves.
 *
 * <p>Contract notes (Keycloak 26 Organizations, GA), pinned by the Testcontainers Keycloak IT:
 * <ul>
 *   <li>Add: {@code POST /organizations/{orgId}/members} whose body is the user id as a BARE JSON
 *       string ({@code "abc-123"}), matching the server's {@code addMember(String)} signature —
 *       sending {@code {"id":"…"}} is rejected as "User does not exist".</li>
 *   <li>Re-adding an existing member 409s; that is an idempotent no-op, not a failure.</li>
 * </ul>
 */
@Component
class ProviderOrgMembershipImpl implements ProviderOrgMembership {

    private static final Logger log = LoggerFactory.getLogger(ProviderOrgMembershipImpl.class);

    private final RestClient keycloakAdminRestClient;
    private final PersonResolver resolver;

    ProviderOrgMembershipImpl(RestClient keycloakAdminRestClient, PersonResolver resolver) {
        this.keycloakAdminRestClient = keycloakAdminRestClient;
        this.resolver = resolver;
    }

    @Override
    public void attach(UUID personId, String externalOrgId) {
        String subject = subjectOrSkip(personId, "attach to");
        if (subject == null) {
            return;
        }
        keycloakAdminRestClient.post()
                .uri("/organizations/{orgId}/members", externalOrgId)
                .contentType(MediaType.APPLICATION_JSON)
                // Bare JSON string body — the endpoint consumes String, not a representation.
                .body("\"" + subject + "\"")
                .retrieve()
                .onStatus(status -> status.value() == 409, (request, response) -> {
                    // Already a member — idempotent no-op.
                })
                .toBodilessEntity();
    }

    @Override
    public void detach(UUID personId, String externalOrgId) {
        String subject = subjectOrSkip(personId, "detach from");
        if (subject == null) {
            return;
        }
        keycloakAdminRestClient.delete()
                .uri("/organizations/{orgId}/members/{userId}", externalOrgId, subject)
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * A person with no Keycloak link has nothing to attach or detach over there, so both calls are a
     * logged no-op rather than an error. That is this module's decision to make and state — the caller
     * cannot tell which providers a person is federated at, and should not have to.
     */
    private String subjectOrSkip(UUID personId, String what) {
        String subject = resolver.keycloakSubjectOf(personId).orElse(null);
        if (subject == null) {
            log.debug("Person {} has no Keycloak link; nothing to {} the provider organization.", personId, what);
        }
        return subject;
    }
}
