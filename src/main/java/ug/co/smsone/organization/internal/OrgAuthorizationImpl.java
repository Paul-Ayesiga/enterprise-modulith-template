package ug.co.smsone.organization.internal;

import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.security.OrgAuthorization;

/** Implements the shared {@link OrgAuthorization} port — makes {@code ApiPermissionEvaluator} live. */
@Component
class OrgAuthorizationImpl implements OrgAuthorization {

    private final PermissionResolver resolver;

    OrgAuthorizationImpl(PermissionResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public boolean hasPermission(String subject, UUID organizationId, String permissionCode) {
        return resolver.resolve(subject, organizationId).contains(permissionCode);
    }

    @Override
    public Set<String> permissions(String subject, UUID organizationId) {
        return resolver.resolve(subject, organizationId);
    }
}
