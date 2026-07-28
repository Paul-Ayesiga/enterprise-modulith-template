package ug.co.smsone.shared.security;

import java.util.Set;
import java.util.UUID;

/**
 * Port for org-scoped authorization, implemented by the {@code organization} module. Resolves a
 * subject's effective permissions within an organization (membership → role → permissions). When no
 * implementation is present, {@link ApiPermissionEvaluator} default-denies — so {@code shared} never
 * compile-depends on {@code organization}.
 */
public interface OrgAuthorization {

    boolean hasPermission(String subject, UUID organizationId, String permissionCode);

    Set<String> permissions(String subject, UUID organizationId);
}
