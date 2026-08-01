package ug.co.smsone.shared.security;

import java.util.UUID;

/**
 * The effective identity of a request running inside an impersonation session: the operator
 * ({@code actorSubject}) wearing the target's identity ({@code targetSubject}), scoped to the tenant
 * the session was opened against ({@code activeOrgId}, {@code null} for an unscoped session).
 *
 * <p>Both subjects are carried, always. Keeping only the target once the swap has happened is what
 * would turn impersonation from an audited oversight tool into an untraceable one — {@code
 * audit_log.actor} can name the accountable human only because this record still knows who they are.
 *
 * <p>{@code writeCapable} mirrors the session's mode. Enforcing it is an HTTP concern — which request
 * methods are safe — and so lives in the filter, not here.
 */
public record ImpersonatedPrincipal(UUID sessionId, String actorSubject, String targetSubject,
        String targetEmail, UUID activeOrgId, boolean writeCapable) {
}
