package ug.co.smsone.organization.internal;

import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.security.OrgAuthorization;

/**
 * Implements the shared {@link OrgAuthorization} port. {@code permissions} is what the edge calls once
 * per request to fill {@code CurrentUser.permissions()}; {@code hasPermission} serves the callers with
 * no request context — a scheduled job authorizing the person who registered the schedule.
 */
@Component
class OrgAuthorizationImpl implements OrgAuthorization {

    private final PermissionResolver resolver;

    OrgAuthorizationImpl(PermissionResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public boolean hasPermission(UUID personId, UUID organizationId, String permissionCode) {
        return resolver.resolve(personId, organizationId).contains(permissionCode);
    }

    @Override
    public Set<String> permissions(UUID personId, UUID organizationId) {
        return resolver.resolve(personId, organizationId);
    }
}
