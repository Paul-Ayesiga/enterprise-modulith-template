package ug.co.smsone.organization.internal;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import ug.co.smsone.organization.Permission;
import ug.co.smsone.shared.error.ForbiddenException;
import ug.co.smsone.shared.security.ApiKeyAuthenticationToken;
import ug.co.smsone.shared.security.ApiKeyPrincipal;
import ug.co.smsone.shared.security.CurrentUser;
import ug.co.smsone.shared.security.CurrentUserProvider;

/**
 * Privilege-escalation guard: the caller may only grant permissions they themselves currently hold
 * in the org. Applies to every path that hands permissions to a subject — creating/updating a role
 * (its permission bundle) and inviting/re-roling a member (the target role's bundle). Without the
 * latter, {@code member:role:assign} alone is equivalent to OWNER: assign yourself the OWNER role.
 * OWNER holds everything, so it is unconstrained.
 */
@Component
class PermissionEscalationGuard {

    private final PermissionResolver permissions;
    private final CurrentUserProvider currentUser;

    PermissionEscalationGuard(PermissionResolver permissions, CurrentUserProvider currentUser) {
        this.permissions = permissions;
        this.currentUser = currentUser;
    }

    void requireCallerHolds(UUID orgId, Set<Permission> granted) {
        // A machine caller is never a member, so the subject lookup below would resolve to nothing
        // and every key-driven grant would 403. Its held set is the key's minted permission subset:
        // capped at mint by a human who HELD those permissions, so the no-escalation invariant
        // carries transitively — a key hands on at most what its minter could have handed on, and
        // only inside its own org (strict id equality, same rule as the evaluator's machine branch).
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof ApiKeyAuthenticationToken apiKey) {
            ApiKeyPrincipal principal = apiKey.getPrincipal();
            if (principal.orgId() == null || !principal.orgId().equals(orgId)) {
                throw new ForbiddenException("This API key cannot grant permissions in this organization.");
            }
            requireHeld(principal.permissions(), granted, "You cannot grant permissions you do not hold: ");
            return;
        }
        CurrentUser caller = currentUser.requireCurrentUser();
        require(orgId, caller.subject(), granted, "You cannot grant permissions you do not hold: ");
    }

    /**
     * The same rule for DEFERRED grants — work submitted by a subject but executed later with no
     * authenticated caller (exchange imports). Checked against that subject's permissions AS OF
     * NOW, so a revocation between submit and processing takes effect.
     */
    void requireSubjectHolds(UUID orgId, String subject, Set<Permission> granted) {
        require(orgId, subject, granted,
                "The requester cannot grant permissions they do not hold: ");
    }

    private void require(UUID orgId, String subject, Set<Permission> granted, String message) {
        requireHeld(permissions.resolve(subject, orgId), granted, message);
    }

    private static void requireHeld(Set<String> held, Set<Permission> granted, String message) {
        List<String> escalated = granted.stream()
                .map(Permission::code)
                .filter(code -> !held.contains(code))
                .sorted()
                .toList();
        if (!escalated.isEmpty()) {
            throw new ForbiddenException(message + String.join(", ", escalated));
        }
    }
}
