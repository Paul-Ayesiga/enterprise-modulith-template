package ug.co.smsone.shared.security;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.error.UnauthorizedException;
import ug.co.smsone.shared.tenancy.TenantContext;

/**
 * The identity resolution layer, and the ONLY one. A validated credential arrives here and leaves as a
 * {@link CurrentUser}: JWT → {@code external_identity} → person → organization → memberships. Below this
 * class no module performs an identity-provider lookup, and adding a second provider is a row in
 * {@code external_identity} rather than a change in this file.
 *
 * <p>The provider-shaped facts all die here. {@code iss}/{@code sub} become a {@code person.id} through
 * {@link PersonLookup}; the {@code organization} claim becomes an {@code organization.id} through
 * {@link OrgLookup}; the membership's role becomes a permission set through {@link OrgAuthorization}.
 * Each port is resolved through an {@code ObjectProvider} and each absence default-DENIES — no person,
 * no tenant, no permissions — because {@code shared} must not compile-depend on the modules that own
 * those tables and an unanswerable question is not a yes.
 *
 * <p><b>Resolved once per request.</b> Those three lookups are database reads, and the callers here are
 * many (the MDC filter, the rate limiter, the idempotency filter, the permission evaluator, controllers,
 * the auditor on every flush), so the result is memoized on the thread against the exact
 * {@code Authentication} instance it was resolved from. That identity check is the invalidation rule:
 * {@code ImpersonationFilter} swaps in a different instance, so a session cannot be served the operator's
 * resolution, and a pooled thread cannot serve the previous request's — no entry ever matches an
 * {@code Authentication} it was not built from. {@link CurrentUserFilter} clears it on the way out and
 * warms it on the way in; the warm-up is what keeps the JPA auditor from issuing a query in the middle of
 * a flush.
 *
 * <p><b>Resolution straddles both tenancy tiers, and since Phase 2 it says so.</b> Every caller pins
 * PLATFORM around this class (ADR 0010 §3.4) because the identity half is platform-tier —
 * {@code external_identity} (§2 table 13), {@code person} (37), {@code external_organization} (14),
 * {@code organization} (35). But {@link #permissions} reaches {@code membership} (26), {@code org_role}
 * (31) and {@code role_permission} (43), and all three are TENANT tier. Through Phase 1 that was
 * invisible, because every table lived in one schema and both halves resolved identically; Phase 2 moved
 * them, and a single flat PLATFORM pin around {@code currentUser()} now reads the identity half correctly
 * and fails on the permission half with {@code relation "membership" does not exist} — which at the edge
 * becomes a 500 on a request that should have been a plain 403.
 *
 * <p>So resolution is <b>two axes in sequence, not one wider pin</b>: person and organization on the
 * caller's PLATFORM axis, then {@code callAs(orgId, …)} for the permission set alone. The second axis is
 * only expressible once the org is known, which is why it lives here rather than at the four call sites
 * that pin around this class — and why widening their pin would be the wrong repair, since a tenant axis
 * over the whole method would be a lie about {@code person} and a promoted tenant would then look for the
 * person graph in its own silo.
 */
@Component
public class CurrentUserProvider {

    private static final String ROLE_PREFIX = "ROLE_";
    private static final String ORGANIZATION_CLAIM = "organization";

    /**
     * One entry, keyed by the identity of the {@code Authentication} it came from. Not a cache with a
     * TTL and not a shared map: it is the memo for the request currently on this thread.
     */
    private static final ThreadLocal<Resolution> RESOLVED = new ThreadLocal<>();

    private final RoleHierarchy roleHierarchy;
    private final ObjectProvider<PersonLookup> persons;
    private final ObjectProvider<OrgLookup> organizations;
    private final ObjectProvider<OrgAuthorization> orgAuthorization;

    public CurrentUserProvider(RoleHierarchy roleHierarchy, ObjectProvider<PersonLookup> persons,
            ObjectProvider<OrgLookup> organizations, ObjectProvider<OrgAuthorization> orgAuthorization) {
        this.roleHierarchy = roleHierarchy;
        this.persons = persons;
        this.organizations = organizations;
        this.orgAuthorization = orgAuthorization;
    }

    public Optional<CurrentUser> currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return Optional.empty();
        }
        Resolution memo = RESOLVED.get();
        if (memo != null && memo.authentication() == authentication) {
            return Optional.of(memo.user());
        }
        CurrentUser user = resolve(authentication);
        if (user == null) {
            return Optional.empty(); // anonymous, or an authentication shape the edge does not mint
        }
        RESOLVED.set(new Resolution(authentication, user));
        return Optional.of(user);
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

    /**
     * The person behind this request — the platform's only durable answer to "who". Empty when nobody
     * is authenticated, when a machine key is calling (a robot is not a person), and when a validated
     * token has no {@code external_identity} link yet.
     *
     * <p>Under impersonation this is the EFFECTIVE person — the target, not the operator — on purpose:
     * {@code created_by} describes the identity the request ran as, and an impersonated write should
     * land in the target's history exactly as their own would.
     * {@link CurrentUser#accountablePersonId()} is the accessor for "which human is answerable", and
     * {@code audit_log} is the one place that records it.
     */
    public Optional<UUID> currentPersonId() {
        return currentUser().map(CurrentUser::personId);
    }

    /**
     * A namespaced bucket key for per-caller partitioning — rate-limit buckets, idempotency scoping.
     * {@code person:<uuid>} for a human, {@code key:<uuid>} for a machine credential.
     *
     * <p>It is a KEY, not an identity: never store it in a column, never compare it to a person id, never
     * put it on the wire. The prefix exists because the two id spaces are unrelated — without it a person
     * and an API key could collide in one namespace, and the whole reason a machine no longer pretends to
     * have a subject is that such collisions were being papered over.
     *
     * <p>Machines need one too, and per KEY rather than per org: two keys in one tenant hold different
     * permission subsets, so sharing an idempotency namespace would let the narrower key replay a stored
     * response to a request it could never have made.
     *
     * <p>Empty when there is no authenticated caller, and — deliberately — for a token with no person yet:
     * such a caller is about to be refused by the provisioning gate, and inventing a durable key for an
     * identity that does not exist would outlive the request that invented it.
     */
    public Optional<String> currentPrincipalKey() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof ApiKeyAuthenticationToken apiKey) {
            return Optional.of("key:" + apiKey.getPrincipal().keyId());
        }
        return currentPersonId().map(personId -> "person:" + personId);
    }

    /** Drops this thread's memo. Called by {@link CurrentUserFilter} once the request is done. */
    static void clear() {
        RESOLVED.remove();
    }

    private CurrentUser resolve(Authentication authentication) {
        // Impersonation is checked first: while a session is active the swapped token is the ONLY
        // authentication on the thread, and resolving it as "no principal" would silently de-attribute
        // every audit row written during the session.
        if (authentication instanceof ImpersonatedAuthenticationToken impersonated) {
            return impersonating(impersonated.getPrincipal());
        }
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            return fromToken(jwtAuthentication);
        }
        if (authentication instanceof ApiKeyAuthenticationToken apiKey) {
            return fromKey(apiKey);
        }
        return null;
    }

    /**
     * The whole flow, in order: the token's own {@code iss}/{@code sub} → person, its {@code organization}
     * claim → tenant, then that person's memberships in that tenant → permissions.
     *
     * <p>The issuer comes from the TOKEN, not from configuration. It is the string the resource server
     * just validated, so it is byte-identical to what {@code external_identity.issuer} holds, and a
     * deployment that later trusts two issuers keeps resolving both without a second key to keep in step.
     */
    private CurrentUser fromToken(JwtAuthenticationToken jwtAuthentication) {
        Jwt jwt = jwtAuthentication.getToken();
        String issuer = jwt.getIssuer() == null ? null : jwt.getIssuer().toString();
        UUID personId = personId(issuer, jwt.getSubject());
        UUID organizationId = organizationId(issuer, jwt);
        return new CurrentUser(personId, organizationId, roles(jwtAuthentication.getAuthorities()),
                permissions(personId, organizationId), AuthenticationMethod.OIDC, null);
    }

    /**
     * The machine view: an organization, a minted permission subset, and NO person — the shape a robot
     * actually has. Nothing is resolved from the database here; an API key already names its tenant and
     * carries its own authority, which is why a key costs the edge no lookups at all.
     *
     * <p>A PLATFORM key has no org and no permissions, only its tier as a role — so every org-scoped
     * check denies it, exactly as before. Roles run through the same hierarchy expansion humans get, so
     * {@code hasRole('platform-support')} and {@code @PreAuthorize("hasRole('platform-support')")} cannot
     * disagree for a machine the way they used to.
     */
    private CurrentUser fromKey(ApiKeyAuthenticationToken apiKey) {
        ApiKeyPrincipal principal = apiKey.getPrincipal();
        return new CurrentUser(null, principal.orgId(), roles(apiKey.getAuthorities()),
                principal.permissions(), AuthenticationMethod.API_KEY, null);
    }

    /**
     * The impersonated view: identity is the target's, authority is nobody's.
     *
     * <p>Roles are empty rather than the actor's — the platform tier that authorized the session does not
     * travel into it, which is what keeps {@code /admin/**} unreachable from inside one. Org permissions
     * ARE resolved, for the target, exactly as they would be for the target's own request: that is what
     * makes tenant endpoints work inside a session, and it is resolved rather than remembered so a
     * membership revoked mid-session stops the very next request.
     */
    private CurrentUser impersonating(ImpersonatedPrincipal principal) {
        return new CurrentUser(
                principal.targetPersonId(),
                principal.activeOrgId(),
                Set.of(),
                permissions(principal.targetPersonId(), principal.activeOrgId()),
                AuthenticationMethod.IMPERSONATION,
                new CurrentUser.Impersonation(principal.sessionId(), principal.actorPersonId()));
    }

    private UUID personId(String issuer, String subject) {
        PersonLookup lookup = persons.getIfAvailable();
        if (lookup == null || issuer == null || subject == null || subject.isBlank()) {
            return null;
        }
        return lookup.personId(issuer, subject).orElse(null);
    }

    /**
     * Parse Keycloak's alias-keyed {@code organization} claim ({@code {"acme":{"id":"…"}}}) and resolve it
     * through {@link OrgLookup}. Only a single-org token yields an active org: a token scoped to zero or
     * to several tenants does not name one, and guessing which of several was meant is how a request ends
     * up authorized against a tenant the caller never asked for.
     *
     * <p>The claim's id is passed through as the string it is. It is a provider identifier — this layer's
     * job is to hand it to the table that maps it, not to have an opinion about its shape.
     */
    private UUID organizationId(String issuer, Jwt jwt) {
        OrgLookup lookup = organizations.getIfAvailable();
        Map<String, Object> claim = jwt.getClaimAsMap(ORGANIZATION_CLAIM);
        if (lookup == null || issuer == null || claim == null || claim.size() != 1) {
            return null;
        }
        Map.Entry<String, Object> entry = claim.entrySet().iterator().next();
        String externalOrgId = entry.getValue() instanceof Map<?, ?> value && value.get("id") instanceof String id
                ? id
                : null;
        return lookup.organizationId(issuer, externalOrgId, entry.getKey()).orElse(null);
    }

    /**
     * What this person may do inside this organization. Empty — never null, never "all" — when either
     * half is missing or no policy is wired: the caller then holds no org authority, which is the same
     * answer {@link ApiPermissionEvaluator} used to reach by default-denying on an absent port.
     *
     * <p><b>The TENANT half of the resolution</b> (see the class note). {@code membership},
     * {@code org_role} and {@code role_permission} are tenant-tier, so this read — and only this read —
     * runs on the organization's own axis; everything else {@code currentUser()} touches is platform-tier
     * and stays on the caller's PLATFORM pin. {@code callAs} rather than {@code set}: it restores the
     * caller's axis in a {@code finally}, so {@code CurrentUserFilter} still decides for itself what the
     * rest of the chain runs on, and nothing is left pinned when the permission read throws.
     *
     * <p>The org is known by now and never null here, so this is a real tenant axis rather than the pooled
     * probe the org-less sweeps use — it routes a promoted tenant correctly for free at Phase 5, which a
     * schema name written into a query could not.
     *
     * <p>It also has to be pinned OUTSIDE any transaction, which it is: {@code CurrentUserFilter} and
     * {@code McpToolDispatcher} both warm the memo above before opening one, so the only callers that
     * reach this method from inside a transaction — {@code AuditLogImpl} on every flush — are served the
     * memo and never get here. If that ever stops being true, {@code TenantContext} says so loudly rather
     * than pinning a connection that is already bound.
     */
    private Set<String> permissions(UUID personId, UUID organizationId) {
        OrgAuthorization authz = orgAuthorization.getIfAvailable();
        if (authz == null || personId == null || organizationId == null) {
            return Set.of();
        }
        Set<String> permissions =
                TenantContext.callAs(organizationId, () -> authz.permissions(personId, organizationId));
        return permissions == null ? Set.of() : permissions;
    }

    /**
     * Platform roles, expanded through the hierarchy so {@link CurrentUser#hasRole(String)} answers the
     * same question {@code @PreAuthorize("hasRole(…)")} does — otherwise a superadmin would fail a
     * hand-rolled {@code hasRole(SUPPORT)} check while passing the annotation, and the two would drift.
     */
    private Set<String> roles(Collection<? extends GrantedAuthority> authorities) {
        return roleHierarchy.getReachableGrantedAuthorities(authorities).stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith(ROLE_PREFIX))
                .map(authority -> authority.substring(ROLE_PREFIX.length()))
                .collect(Collectors.toUnmodifiableSet());
    }

    private record Resolution(Authentication authentication, CurrentUser user) {
    }
}
