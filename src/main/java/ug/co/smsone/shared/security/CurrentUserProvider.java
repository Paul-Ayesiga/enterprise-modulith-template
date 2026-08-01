package ug.co.smsone.shared.security;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.error.UnauthorizedException;

/** Resolves the {@link CurrentUser} from the security context (services, auditing, jobs). */
@Component
public class CurrentUserProvider {

    private static final Logger log = LoggerFactory.getLogger(CurrentUserProvider.class);
    private static final String ROLE_PREFIX = "ROLE_";
    private static final String EMAIL_CLAIM = "email";
    private static final String ORGANIZATION_CLAIM = "organization";

    private final RoleHierarchy roleHierarchy;

    public CurrentUserProvider(RoleHierarchy roleHierarchy) {
        this.roleHierarchy = roleHierarchy;
    }

    public Optional<CurrentUser> currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // Impersonation is checked first: while a session is active the swapped token is the ONLY
        // authentication on the thread, and resolving it as "no principal" would silently de-attribute
        // every audit row written during the session.
        if (authentication instanceof ImpersonatedAuthenticationToken impersonated) {
            return Optional.of(impersonating(impersonated.getPrincipal()));
        }
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            return Optional.of(fromToken(jwtAuthentication));
        }
        return Optional.empty();
    }

    /**
     * The caller, or a 401 — the one spelling of "this operation needs a principal".
     *
     * <p>Here rather than at each call site because {@code detail} is on the wire: five hand-written
     * {@code orElseThrow}s were five chances for one to drift, and one already had, so the same missing
     * principal rendered two different bodies depending on which path noticed it first.
     */
    public CurrentUser requireCurrentUser() {
        return currentUser().orElseThrow(() -> new UnauthorizedException("Authentication required."));
    }

    private CurrentUser fromToken(JwtAuthenticationToken jwtAuthentication) {
        Jwt jwt = jwtAuthentication.getToken();
        // Expanded through the hierarchy so CurrentUser.hasRole() answers the same question
        // @PreAuthorize("hasRole(…)") does — otherwise a superadmin would fail a hand-rolled
        // hasRole(SUPPORT) check while passing the annotation, and the two would drift apart.
        Set<String> roles = roleHierarchy.getReachableGrantedAuthorities(jwtAuthentication.getAuthorities()).stream()
                .map(authority -> authority.getAuthority())
                .filter(authority -> authority.startsWith(ROLE_PREFIX))
                .map(authority -> authority.substring(ROLE_PREFIX.length()))
                .collect(Collectors.toUnmodifiableSet());
        ActiveOrg activeOrg = resolveActiveOrg(jwt);
        return new CurrentUser(
                jwt.getSubject(),
                jwtAuthentication.getName(),
                jwt.getClaimAsString(EMAIL_CLAIM),
                roles,
                activeOrg.alias(),
                activeOrg.id(),
                null);
    }

    /**
     * The impersonated view: identity is the target's, authority is nobody's.
     *
     * <p>Roles are empty rather than the actor's — the platform tier that authorized the session does
     * not travel into it, which is what keeps {@code /admin/**} unreachable from inside one. The org
     * alias is null because the session records only an org <em>id</em>: the alias is a display and
     * URL convenience, and {@link ApiPermissionEvaluator} matches on the id alone by design.
     */
    private CurrentUser impersonating(ImpersonatedPrincipal principal) {
        return new CurrentUser(
                principal.targetSubject(),
                // username() is display-only, and a session carries no preferred_username; the target's
                // email is the humane handle, their subject the honest fallback when it is unknown.
                principal.targetEmail() == null ? principal.targetSubject() : principal.targetEmail(),
                principal.targetEmail(),
                Set.of(),
                null,
                principal.activeOrgId(),
                new CurrentUser.Impersonation(principal.sessionId(), principal.actorSubject()));
    }

    /**
     * The caller's <em>stable</em> identity — the token subject — for anything durable: audit
     * attribution, idempotency scoping, rate-limit buckets.
     *
     * <p>Never key on {@link CurrentUser#username()}. That is Keycloak's {@code preferred_username},
     * which is mutable and, once freed, reassignable: a renamed account would lose its own records and
     * whoever inherited the name would gain them. The subject is immutable for the life of the account.
     *
     * <p>Under impersonation this is the EFFECTIVE subject — the target, not the operator — on purpose:
     * rate-limit buckets, idempotency keys and {@code created_by} all describe the identity the request
     * ran as, and an impersonated write should land in the target's history exactly as their own would.
     * {@link CurrentUser#accountableSubject()} is the accessor for "which human is answerable", and
     * {@code audit_log} is the one place that records it.
     *
     * <p>Empty when the request has no authenticated principal.
     */
    public Optional<String> currentSubject() {
        return currentUser().map(CurrentUser::subject);
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
