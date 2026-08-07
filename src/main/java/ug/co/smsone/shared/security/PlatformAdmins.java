package ug.co.smsone.shared.security;

import java.util.UUID;

/**
 * Platform super-admin membership. A cross-cutting port (the role data lives in the identity provider,
 * the guard that needs it lives elsewhere) so a caller can refuse an action that would leave the
 * platform with no super-admin — without depending on the identity module. Implemented in
 * {@code identity.internal}; consumers inject it through an {@code ObjectProvider} and, when it is
 * absent, must NOT block (this guards a right, so its default is permissive, not deny).
 */
public interface PlatformAdmins {

    /**
     * True when this person holds {@code platform-superadmin} AND is the only account that does.
     *
     * <p>Takes a {@code person.id} because its caller — the erasure guard — is reasoning about a human,
     * not about a credential. Translating that person into whatever the identity provider calls them is
     * identity's own business, and the only module that can do it correctly once a second provider
     * exists.
     */
    boolean isSoleSuperAdmin(UUID personId);
}
