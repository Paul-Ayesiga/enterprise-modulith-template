package ug.co.smsone.identity;

import java.util.Optional;

/**
 * Public read port: look up a provisioned user's immutable Keycloak subject ({@code sub}). Lets
 * other modules address users by their stable id instead of mutable/recyclable identifiers
 * (usernames, emails) — e.g. in-app notifications are stored per-subject.
 */
public interface UserDirectory {

    /** The subject of the provisioned user with this email, if any (case-insensitive match). */
    Optional<String> findSubjectByEmail(String email);
}
