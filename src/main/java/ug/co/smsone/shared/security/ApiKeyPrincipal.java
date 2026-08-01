package ug.co.smsone.shared.security;

import java.util.Set;
import java.util.UUID;

/**
 * An authenticated machine credential. Exactly one of the two authority shapes is present: an org
 * key carries {@code orgId} + a permission SUBSET (capped at mint by what its creator held); a
 * platform key carries {@code platformTier} (today always {@code platform-support} — machines
 * read, humans change). {@code subject()} is the durable attribution string audit rows get.
 */
public record ApiKeyPrincipal(UUID keyId, String name, UUID orgId, Set<String> permissions,
        String platformTier) {

    public String subject() {
        return "key:" + keyId;
    }
}
