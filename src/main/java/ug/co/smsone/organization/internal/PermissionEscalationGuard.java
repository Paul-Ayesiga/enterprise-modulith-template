package ug.co.smsone.organization.internal;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ug.co.smsone.organization.Permission;
import ug.co.smsone.shared.error.ForbiddenException;
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
        CurrentUser caller = currentUser.requireCurrentUser();
        Set<String> held = permissions.resolve(caller.subject(), orgId);
        List<String> escalated = granted.stream()
                .map(Permission::code)
                .filter(code -> !held.contains(code))
                .sorted()
                .toList();
        if (!escalated.isEmpty()) {
            throw new ForbiddenException("You cannot grant permissions you do not hold: "
                    + String.join(", ", escalated));
        }
    }
}
