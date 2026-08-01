package ug.co.smsone.shared.security;

import java.util.Set;
import java.util.UUID;

/**
 * Authenticated-user abstraction decoupling controllers/services from JWT internals. Inject as a
 * controller method parameter (resolved by {@link CurrentUserArgumentResolver}) or obtain via
 * {@link CurrentUserProvider} in services.
 *
 * <p>{@code activeOrg*} come from the token's Keycloak {@code organization} claim — the tenant the
 * request is scoped to. They are {@code null} when the token targets zero or more than one org
 * (org-scoped {@code hasPermission} checks then deny until the client requests a single-org scope).
 *
 * <p>Under impersonation every field describes the <em>effective</em> identity — the target — and
 * {@code roles} is empty, because the impersonated principal holds no platform role. The operator
 * behind the request is in {@link #impersonation()}; {@link #accountableSubject()} is the one accessor
 * that answers "which human is answerable for this", and it is what the audit trail records.
 */
public record CurrentUser(String subject, String username, String email, Set<String> roles,
        String activeOrgAlias, UUID activeOrgId, Impersonation impersonation) {

    /**
     * The oversight session behind this request, {@code null} when the caller is simply themselves.
     * Nested rather than two loose components so the pair cannot half-arrive: an actor subject without
     * a session id is an impersonation nobody can trace back to its recorded reason.
     */
    public record Impersonation(UUID sessionId, String actorSubject) {
    }

    /**
     * True when the caller holds this role <em>or a higher platform tier</em> — {@code roles} arrives
     * already expanded through the {@code RoleHierarchy}, so this and
     * {@code @PreAuthorize("hasRole(…)")} always agree. Always false while impersonating.
     */
    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean isImpersonated() {
        return impersonation != null;
    }

    /**
     * The real human answerable for this request: the operator when impersonating, the caller
     * otherwise. This — not {@link #subject()} — is what {@code audit_log.actor} holds, which is the
     * one place in the system where the two deliberately differ.
     */
    public String accountableSubject() {
        return impersonation == null ? subject : impersonation.actorSubject();
    }
}
