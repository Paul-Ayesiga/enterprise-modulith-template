package ug.co.smsone.shared.security;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The authenticated caller, resolved ONCE at the edge and stated in this platform's own vocabulary:
 * a {@code person.id}, an {@code organization.id}, the two authority axes, and how they authenticated.
 * Inject as a controller parameter (resolved by {@link CurrentUserArgumentResolver}) or obtain via
 * {@link CurrentUserProvider} in services.
 *
 * <p><b>There is no subject here, and no Keycloak organization id.</b> A token's {@code iss}/{@code sub}
 * pair and its {@code organization} claim are provider identifiers; they are translated at the edge —
 * through {@link PersonLookup} and {@link OrgLookup} — and never travel further. That is the whole
 * point: a second identity provider becomes a row in {@code external_identity}, not a branch in a
 * module that happens to need to know who is calling.
 *
 * <p>{@code personId} is null for a machine key and for a validated token that has no person yet; see
 * {@link AuthenticationMethod} for which is which. {@code organizationId} is null when the caller is
 * not scoped to exactly one tenant — org-scoped {@code hasPermission} checks then deny.
 *
 * <p>The two authority axes are disjoint by design (AGENTS §1): {@code roles} is the platform tier
 * ladder, {@code permissions} is what this caller may do INSIDE {@code organizationId} — a membership's
 * role for a person, the minted subset for a machine key. No role appears in {@code permissions} and no
 * permission implies a role.
 *
 * <p>Under impersonation every field describes the <em>effective</em> identity — the target — and
 * {@code roles} is empty, because the impersonated principal holds no platform tier. The operator behind
 * the request is in {@link #impersonation()}; {@link #accountablePersonId()} is the one accessor that
 * answers "which human is answerable for this", and it is what {@code audit_log.actor} records.
 */
public record CurrentUser(UUID personId, UUID organizationId, Set<String> roles, Set<String> permissions,
        AuthenticationMethod authenticationMethod, Impersonation impersonation) {

    /**
     * The oversight session behind this request, {@code null} when the caller is simply themselves.
     * Nested rather than two loose components so the pair cannot half-arrive: an actor without a session
     * id is an impersonation nobody can trace back to its recorded reason.
     */
    public record Impersonation(UUID sessionId, UUID actorPersonId) {
    }

    public CurrentUser {
        Objects.requireNonNull(authenticationMethod, "authenticationMethod");
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        // The method and the session must agree. They are two spellings of one fact, and a request
        // carrying a session while claiming to be an ordinary login (or the reverse) would make
        // "is this impersonated" answerable two ways — the exact ambiguity every audit rule here
        // depends on not existing.
        if ((authenticationMethod == AuthenticationMethod.IMPERSONATION) != (impersonation != null)) {
            throw new IllegalArgumentException(
                    "IMPERSONATION and an impersonation session must arrive together, or neither.");
        }
    }

    /**
     * True when the caller holds this platform role <em>or a higher tier</em> — {@code roles} arrives
     * already expanded through the {@code RoleHierarchy}, so this and {@code @PreAuthorize("hasRole(…)")}
     * always agree. Always false while impersonating.
     */
    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    /**
     * Whether the caller may do {@code permissionCode} in {@code organizationId}. Both halves are checked
     * here, together, on purpose: {@link #permissions()} is scoped to {@link #organizationId()} and to
     * nothing else, so a caller that compared the codes without re-checking the tenant would authorize
     * org A's permissions against org B — the tenant-isolation break {@link ApiPermissionEvaluator}'s
     * strict-id rule exists to prevent.
     */
    public boolean hasPermission(UUID organizationId, String permissionCode) {
        return this.organizationId != null && this.organizationId.equals(organizationId)
                && permissions.contains(permissionCode);
    }

    /**
     * True when the caller is a machine credential — no person exists behind this request and none is
     * missing. Anything that asks "has this human been provisioned" must ask this first; the answer for
     * a machine is that the question does not apply (an admin minted the key, which already answered it).
     */
    public boolean isMachine() {
        return authenticationMethod == AuthenticationMethod.API_KEY;
    }

    public boolean isImpersonated() {
        return impersonation != null;
    }

    /**
     * The person answerable for this request: the operator when impersonating, the caller otherwise;
     * {@code null} when a machine key is calling — no person is accountable for a robot, and
     * {@code created_by} takes NULL for exactly that case (V10).
     *
     * <p>This — not {@link #personId()} — is what {@code audit_log.actor} holds, which is the one place
     * in the system where the two deliberately differ.
     */
    public UUID accountablePersonId() {
        return impersonation == null ? personId : impersonation.actorPersonId();
    }
}
