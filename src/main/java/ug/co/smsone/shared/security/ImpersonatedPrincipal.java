package ug.co.smsone.shared.security;

import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;

/**
 * The effective identity of a request running inside an impersonation session: the operator
 * ({@code actorPersonId}) wearing the target's identity ({@code targetPersonId}), scoped to the tenant the
 * session was opened against ({@code activeOrgId}, {@code null} for an unscoped session). All three are
 * this platform's own ids — the session row holds person ids, and the edge has no reason to translate
 * them back into an identity provider's vocabulary to hand them to itself.
 *
 * <p>Both people are carried, always. Keeping only the target once the swap has happened is what would
 * turn impersonation from an audited oversight tool into an untraceable one — {@code audit_log.actor} can
 * name the accountable human only because this record still knows who they are.
 *
 * <p>No display data rides along. A label for the operator's benefit is the session's business (its
 * frozen {@code target_display}) and the API's; the edge's job is who the request IS, and a decision that
 * keyed on a name instead of an id is a bug waiting for two people to share one.
 *
 * <p>{@code writeCapable} mirrors the session's mode. Enforcing it is an HTTP concern — which request
 * methods are safe — and so lives in the filter, not here.
 *
 * <p>{@code Serializable} for the same reason as {@link ApiKeyPrincipal}: it is the principal of a
 * serializable {@code Authentication}, and a non-serializable principal inside one fails only where the
 * context is actually written out, never in a test that builds the token in memory.
 */
public record ImpersonatedPrincipal(UUID sessionId, UUID actorPersonId, UUID targetPersonId,
        UUID activeOrgId, boolean writeCapable) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
