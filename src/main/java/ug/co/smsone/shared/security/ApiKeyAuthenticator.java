package ug.co.smsone.shared.security;

import java.util.Optional;

/**
 * Port for API-key verification — the {@code OrgAuthorization} pattern: the filter lives in
 * {@code shared}, the key store lives in the {@code apikeys} module. Absent implementation means
 * the header cannot be verified, so it is IGNORED (the request proceeds unauthenticated and the
 * bearer path — or a 401 — decides), never trusted.
 */
public interface ApiKeyAuthenticator {

    /** The principal for a presented {@code sk_<prefix>.<secret>} value, if it verifies. */
    Optional<ApiKeyPrincipal> authenticate(String presentedKey);
}
