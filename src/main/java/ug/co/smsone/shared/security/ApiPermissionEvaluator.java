package ug.co.smsone.shared.security;

import java.io.Serializable;
import java.util.UUID;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Object-level authorization seam for method security. For org-scoped checks —
 * {@code @PreAuthorize("hasPermission(#orgId, 'organization', 'member:invite')")} — it answers from the
 * {@link CurrentUser} the edge already resolved: the caller must be scoped to {@code #orgId} AND hold the
 * permission there. Default-deny when there is no caller, no active org, or no permission.
 *
 * <p>One branch, both principal kinds. A person's permissions come from their membership's role and a
 * machine key's from the subset minted into it, but both arrive here as {@link CurrentUser#permissions()}
 * scoped to {@link CurrentUser#organizationId()} — so the machine case can no longer drift from the human
 * one, which is what a second branch reaching into the token guaranteed it eventually would.
 *
 * <p>No role branch, ever (AGENTS §1): a platform tier grants no org permission. Reaching tenant data as
 * a platform operator is impersonation's job, which is audited.
 */
@Component
public class ApiPermissionEvaluator implements PermissionEvaluator {

    private static final String ORG_TARGET = "organization";

    private final CurrentUserProvider currentUserProvider;

    public ApiPermissionEvaluator(CurrentUserProvider currentUserProvider) {
        this.currentUserProvider = currentUserProvider;
    }

    @Override
    public boolean hasPermission(Authentication authentication, Object targetDomainObject, Object permission) {
        return false; // object-form unused for org RBAC
    }

    @Override
    public boolean hasPermission(Authentication authentication, Serializable targetId, String targetType,
            Object permission) {
        if (!ORG_TARGET.equals(targetType)) {
            return false;
        }
        CurrentUser user = currentUserProvider.currentUser().orElse(null);
        if (user == null) {
            return false;
        }
        UUID organizationId = organizationId(targetId);
        if (organizationId == null) {
            return false; // an org id that is not one names no tenant, so it authorizes nothing
        }
        // Strict id equality, inside CurrentUser.hasPermission: the org the endpoint acts on must BE the
        // org the permissions were resolved against. No alias matching — an alias branch would let a
        // token scoped to org A (whose alias string happens to equal org B's id) satisfy the scope check
        // for org B while permissions came from org A: a tenant-isolation break. Alias-addressed URLs
        // resolve the alias to its org id before the check.
        return user.hasPermission(organizationId, String.valueOf(permission));
    }

    /**
     * The target as a tenant key, or null. Parsed rather than string-compared because both sides are now
     * real {@code organization.id} values: comparing renderings would make {@code hasPermission} sensitive
     * to how a caller spelled a UUID it holds.
     */
    private static UUID organizationId(Serializable targetId) {
        if (targetId instanceof UUID id) {
            return id;
        }
        try {
            return UUID.fromString(String.valueOf(targetId));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
