package ug.co.smsone.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ug.co.smsone.shared.error.ErrorCode;
import ug.co.smsone.shared.web.ApiSource;
import ug.co.smsone.shared.web.EnvelopeErrorWriter;
import ug.co.smsone.shared.web.RequestPaths;

/**
 * Applies an impersonation session: a request carrying {@code X-Impersonate: <sessionId>} runs as the
 * session's target instead of the operator who sent it. Absent the header the chain is untouched.
 *
 * <p>{@code @Order(-2)} places it immediately after authentication (the security chain is {@code -100})
 * and before <em>everything</em> else — rate limiting ({@code -1}), idempotency ({@code 0}), the
 * provisioning gate ({@code 1}) and org permission resolution. That is deliberate and load-bearing: the
 * whole downstream request must see ONE effective principal. Swapping later would bucket the operator's
 * rate limit and idempotency keys under their own identity while the endpoint ran as someone else, and
 * would let a session reach an account the provisioning gate refuses to the person themselves.
 *
 * <p>The session is resolved through the {@link ImpersonationLookup} port, so this class — and
 * {@code shared} — never compile-depends on the module that owns the table. No implementation present
 * means the session cannot be authorized, and an unauthorizable session is denied, never ignored.
 *
 * <p>Everything the header could reach is re-decided here on every request, never trusted from the
 * moment the session was opened: the actor's platform tier (below), and — inside the port — both
 * accounts' provisioning state. A session lasts up to its whole TTL, so a revocation that only stopped
 * the <em>next</em> open would leave the reach it already granted running to the clock.
 *
 * <p>{@code app.impersonation.enabled=false} makes the header a 403, never a silent no-op. Ignoring it
 * would be the dangerous reading: the operator's request would succeed as <em>themselves</em> while
 * they believed they were wearing someone else — so they would read their own data thinking it was the
 * customer's, and any write would land under the wrong identity with nothing in the trail to say the
 * header was ever sent. A refusal is unambiguous and self-explaining.
 */
@Component
@Order(-2)
class ImpersonationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ImpersonationFilter.class);
    private static final String IMPERSONATE_HEADER = "X-Impersonate";

    /**
     * HEAD and OPTIONS join GET as safe: HEAD is a GET without a body and OPTIONS is a capability probe,
     * so neither can change state, and refusing them would break ordinary clients (CORS pre-flight,
     * existence checks) inside a read-only session for no gain in safety.
     */
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final ObjectProvider<ImpersonationLookup> lookups;
    private final CurrentUserProvider currentUserProvider;
    private final EnvelopeErrorWriter errorWriter;
    private final boolean enabled;

    ImpersonationFilter(ObjectProvider<ImpersonationLookup> lookups, CurrentUserProvider currentUserProvider,
            EnvelopeErrorWriter errorWriter,
            // Read here rather than through the identity module's properties record: that record is
            // package-private, and shared must not compile-depend on identity. The property NAME is the
            // shared contract; ImpersonationController reads the same one from its own side.
            @Value("${app.impersonation.enabled:true}") boolean enabled) {
        this.lookups = lookups;
        this.currentUserProvider = currentUserProvider;
        this.errorWriter = errorWriter;
        this.enabled = enabled;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !RequestPaths.of(request).startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String sessionId = request.getHeader(IMPERSONATE_HEADER);
        if (sessionId == null || sessionId.isBlank()) {
            chain.doFilter(request, response);
            return;
        }
        if (!enabled) {
            // Refused, not ignored — the caller asked to act as someone else and must not be told yes
            // while acting as themselves. Stated plainly so an operator reads it as configuration
            // rather than as a session of theirs having lapsed.
            deny(request, response, "Impersonation is disabled on this deployment.");
            return;
        }
        // The actor is whoever the token resolved to, never a header value: the session is pinned to the
        // operator it was issued to, so an id copied out of a log line is worthless to whoever copied it.
        CurrentUser actor = currentUserProvider.currentUser().orElse(null);
        if (actor == null) {
            chain.doFilter(request, response); // unauthenticated — the security chain owns that answer
            return;
        }
        UUID actorPersonId = actor.personId();
        if (actorPersonId == null) {
            // A machine key, or a token whose person does not exist yet. Impersonation is an oversight
            // tool one HUMAN answers for; with no person there is nobody to hold accountable, and
            // audit_log.actor would record the absence as a null. Said plainly because a platform key
            // CAN hold the support tier, so the tier check below would otherwise have passed it.
            deny(request, response, "Impersonation requires a signed-in operator.");
            return;
        }
        // Re-checked per request, not trusted from open time: a session lives up to its whole TTL, and
        // revoking the tier that authorized it has to stop the reach it granted rather than wait the
        // clock out. Support is the floor for holding any session at all; the write-capable case asks
        // again for admin below, once the mode is known.
        if (!actor.hasRole(PlatformRole.SUPPORT)) {
            deny(request, response, "That impersonation session is not active.");
            return;
        }

        ImpersonatedPrincipal principal;
        try {
            ImpersonationLookup lookup = lookups.getIfAvailable();
            if (lookup == null) {
                deny(request, response, "Impersonation is not available.");
                return;
            }
            // A malformed, unknown, ended or expired id all arrive here as an empty Optional — the port
            // deliberately does not distinguish them, and neither does the answer the caller gets.
            principal = lookup.activeSession(sessionId.trim(), actorPersonId).orElse(null);
        } catch (RuntimeException ex) {
            // A filter exception would bypass GlobalExceptionHandler and produce a non-envelope 500;
            // render the envelope ourselves (never leak the exception to the client).
            log.error("Impersonation lookup failed for actor {}: {}", actorPersonId, ex.toString(), ex);
            errorWriter.write(request, response, ErrorCode.INTERNAL_ERROR,
                    "The request could not be processed.", null);
            return;
        }
        if (principal == null) {
            deny(request, response, "That impersonation session is not active.");
            return;
        }
        if (principal.writeCapable() && !actor.hasRole(PlatformRole.ADMIN)) {
            // The mode was authorized against the admin tier; losing it must cost the writes, not merely
            // the right to open the next session.
            deny(request, response, "That impersonation session is not active.");
            return;
        }
        if (!principal.writeCapable() && !SAFE_METHODS.contains(request.getMethod())) {
            deny(request, response, "That impersonation session is read-only.");
            return;
        }

        SecurityContext previous = SecurityContextHolder.getContext();
        try {
            SecurityContext impersonated = SecurityContextHolder.createEmptyContext();
            impersonated.setAuthentication(new ImpersonatedAuthenticationToken(principal));
            SecurityContextHolder.setContext(impersonated);
            chain.doFilter(request, response);
        } finally {
            // Restoring is not tidiness: request threads are pooled, and a context left swapped would
            // hand the next request on this thread somebody else's identity.
            SecurityContextHolder.setContext(previous);
        }
    }

    private void deny(HttpServletRequest request, HttpServletResponse response, String detail)
            throws IOException {
        errorWriter.write(request, response, ErrorCode.FORBIDDEN, detail, ApiSource.header(IMPERSONATE_HEADER));
    }
}
