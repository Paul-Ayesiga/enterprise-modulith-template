package ug.co.smsone.shared.security;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** Resolves the {@link CurrentUser} from the security context (services, auditing, jobs). */
@Component
public class CurrentUserProvider {

    private static final Logger log = LoggerFactory.getLogger(CurrentUserProvider.class);
    private static final String ROLE_PREFIX = "ROLE_";
    private static final String EMAIL_CLAIM = "email";
    private static final String ORGANIZATION_CLAIM = "organization";

    public Optional<CurrentUser> currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            return Optional.empty();
        }
        Jwt jwt = jwtAuthentication.getToken();
        Set<String> roles = jwtAuthentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .filter(authority -> authority.startsWith(ROLE_PREFIX))
                .map(authority -> authority.substring(ROLE_PREFIX.length()))
                .collect(Collectors.toUnmodifiableSet());
        ActiveOrg activeOrg = resolveActiveOrg(jwt);
        return Optional.of(new CurrentUser(
                jwt.getSubject(),
                jwtAuthentication.getName(),
                jwt.getClaimAsString(EMAIL_CLAIM),
                roles,
                activeOrg.alias(),
                activeOrg.id()));
    }

    /**
     * Parse the Keycloak {@code organization} claim (alias-keyed: {@code {"acme":{"id":"…"}}}). Only a
     * single-org token yields an active org; a token scoped to zero or multiple orgs leaves it null.
     */
    private ActiveOrg resolveActiveOrg(Jwt jwt) {
        Map<String, Object> organizations = jwt.getClaimAsMap(ORGANIZATION_CLAIM);
        if (organizations == null || organizations.size() != 1) {
            return ActiveOrg.NONE;
        }
        Map.Entry<String, Object> entry = organizations.entrySet().iterator().next();
        UUID id = null;
        if (entry.getValue() instanceof Map<?, ?> value && value.get("id") instanceof String idString) {
            try {
                id = UUID.fromString(idString);
            } catch (IllegalArgumentException ex) {
                log.warn("Ignoring malformed organization id in token: {}", idString);
            }
        }
        return new ActiveOrg(entry.getKey(), id);
    }

    private record ActiveOrg(String alias, UUID id) {
        static final ActiveOrg NONE = new ActiveOrg(null, null);
    }
}
