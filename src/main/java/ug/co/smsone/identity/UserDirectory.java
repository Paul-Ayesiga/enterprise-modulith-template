package ug.co.smsone.identity;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Public read port: look up a provisioned user's immutable Keycloak subject ({@code sub}). Lets
 * other modules address users by their stable id instead of mutable/recyclable identifiers
 * (usernames, emails) — e.g. in-app notifications are stored per-subject.
 */
public interface UserDirectory {

    /** The subject of the provisioned user with this email, if any (case-insensitive match). */
    Optional<String> findSubjectByEmail(String email);

    /**
     * Reverse lookup, batched for windowed readers (the members export resolves one page of
     * subjects per call): subject → email for every subject that has a local projection row.
     * Unknown subjects are simply absent from the map.
     */
    Map<String, String> emailsBySubjects(Collection<String> subjects);

    /**
     * The subject's IdP links (Keycloak federated identities) — READ-ONLY: linking and unlinking
     * are Keycloak account-console acts; this platform displays, it never mutates IdP identity.
     */
    java.util.List<LinkedAccount> linkedAccounts(String subject);

    /** One external identity link: which provider, and the username over there. */
    record LinkedAccount(String provider, String username) {
    }
}
