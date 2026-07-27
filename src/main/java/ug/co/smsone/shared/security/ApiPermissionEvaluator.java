package ug.co.smsone.shared.security;

import java.io.Serializable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Deliberate seam for object-level authorization ({@code hasPermission(...)} in method security).
 * Default-deny until a real policy (RBAC/ABAC) lands; role checks via {@code hasRole} are unaffected.
 */
@Component
public class ApiPermissionEvaluator implements PermissionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ApiPermissionEvaluator.class);

    @Override
    public boolean hasPermission(Authentication authentication, Object targetDomainObject, Object permission) {
        log.debug("hasPermission({}, {}) denied by default-deny policy", targetDomainObject, permission);
        return false;
    }

    @Override
    public boolean hasPermission(Authentication authentication, Serializable targetId, String targetType,
            Object permission) {
        log.debug("hasPermission({}, {}, {}) denied by default-deny policy", targetType, targetId, permission);
        return false;
    }
}
