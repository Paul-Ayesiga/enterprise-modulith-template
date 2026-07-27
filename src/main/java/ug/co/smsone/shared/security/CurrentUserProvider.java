package ug.co.smsone.shared.security;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** Resolves the {@link CurrentUser} from the security context (services, auditing, jobs). */
@Component
public class CurrentUserProvider {

    private static final String ROLE_PREFIX = "ROLE_";
    private static final String EMAIL_CLAIM = "email";

    public Optional<CurrentUser> currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            return Optional.empty();
        }
        Set<String> roles = jwtAuthentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .filter(authority -> authority.startsWith(ROLE_PREFIX))
                .map(authority -> authority.substring(ROLE_PREFIX.length()))
                .collect(Collectors.toUnmodifiableSet());
        return Optional.of(new CurrentUser(
                jwtAuthentication.getToken().getSubject(),
                jwtAuthentication.getName(),
                jwtAuthentication.getToken().getClaimAsString(EMAIL_CLAIM),
                roles));
    }
}
