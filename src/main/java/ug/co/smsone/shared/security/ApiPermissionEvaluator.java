package ug.co.smsone.shared.security;

import java.io.Serializable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Object-level authorization seam for method security. For org-scoped checks —
 * {@code @PreAuthorize("hasPermission(#orgId, 'organization', 'member:invite')")} — it delegates to
 * the {@link OrgAuthorization} port (implemented by the organization module): the caller's token must
 * be scoped to {@code #orgId} (cross-org attempts denied before any DB hit) AND their role in that
 * org must carry the permission. Default-deny when no policy is present or the token has no active org.
 */
@Component
public class ApiPermissionEvaluator implements PermissionEvaluator {

    private static final String ORG_TARGET = "organization";

    private final CurrentUserProvider currentUserProvider;
    private final ObjectProvider<OrgAuthorization> orgAuthorization;

    public ApiPermissionEvaluator(CurrentUserProvider currentUserProvider,
            ObjectProvider<OrgAuthorization> orgAuthorization) {
        this.currentUserProvider = currentUserProvider;
        this.orgAuthorization = orgAuthorization;
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
        if (authentication instanceof ApiKeyAuthenticationToken apiKey) {
            // Machine branch: the key's minted SUBSET is the whole authority — no membership
            // resolution, no role bypass, and the same strict org-id equality humans get.
            ApiKeyPrincipal principal = apiKey.getPrincipal();
            return principal.orgId() != null
                    && String.valueOf(targetId).equals(principal.orgId().toString())
                    && principal.permissions().contains(String.valueOf(permission));
        }
        OrgAuthorization authz = orgAuthorization.getIfAvailable();
        if (authz == null) {
            return false; // no policy wired -> default deny
        }
        CurrentUser user = currentUserProvider.currentUser().orElse(null);
        if (user == null || user.activeOrgId() == null) {
            return false; // token not scoped to a single org -> deny
        }
        // Strict id equality: the org the endpoint acts on must BE the org permissions are resolved
        // against. No alias matching — an alias branch would let a token scoped to org A (whose alias
        // string happens to equal org B's id) satisfy the scope check for org B while permissions are
        // resolved against org A: a tenant-isolation break. Alias-addressed URLs must resolve the
        // alias to its org id before the check.
        if (!String.valueOf(targetId).equals(String.valueOf(user.activeOrgId()))) {
            return false; // acting on an org the token isn't scoped to -> deny
        }
        return authz.hasPermission(user.subject(), user.activeOrgId(), String.valueOf(permission));
    }
}
