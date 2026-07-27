package ug.co.smsone.shared.security;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Maps Keycloak's {@code realm_access.roles} and {@code resource_access.<client>.roles} claims to
 * {@code ROLE_*} authorities; principal name comes from {@code preferred_username}.
 */
@Component
public class KeycloakJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final String REALM_ACCESS = "realm_access";
    private static final String RESOURCE_ACCESS = "resource_access";
    private static final String ROLES = "roles";
    private static final String PREFERRED_USERNAME = "preferred_username";

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Set<GrantedAuthority> authorities = new HashSet<>();
        addRoles(authorities, jwt.getClaimAsMap(REALM_ACCESS));
        Map<String, Object> resourceAccess = jwt.getClaimAsMap(RESOURCE_ACCESS);
        if (resourceAccess != null) {
            for (Object clientAccess : resourceAccess.values()) {
                if (clientAccess instanceof Map<?, ?> clientMap) {
                    addRoles(authorities, clientMap);
                }
            }
        }
        String principalName = jwt.hasClaim(PREFERRED_USERNAME)
                ? jwt.getClaimAsString(PREFERRED_USERNAME)
                : jwt.getSubject();
        return new JwtAuthenticationToken(jwt, authorities, principalName);
    }

    private void addRoles(Set<GrantedAuthority> authorities, Map<?, ?> accessClaim) {
        if (accessClaim == null) {
            return;
        }
        if (accessClaim.get(ROLES) instanceof Collection<?> roles) {
            for (Object role : roles) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
            }
        }
    }
}
