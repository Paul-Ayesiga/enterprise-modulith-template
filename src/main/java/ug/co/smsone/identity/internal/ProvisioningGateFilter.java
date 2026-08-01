package ug.co.smsone.identity.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ug.co.smsone.identity.internal.UserAccessService.Decision;
import ug.co.smsone.shared.error.ErrorCode;
import ug.co.smsone.shared.security.CurrentUser;
import ug.co.smsone.shared.security.CurrentUserProvider;
import ug.co.smsone.shared.web.EnvelopeErrorWriter;
import ug.co.smsone.shared.web.RequestPaths;

/**
 * No-JIT access gate: an authenticated request to {@code /api/**} requires a provisioned
 * {@code app_user}, else 403 — so a valid Keycloak JWT is not, by itself, access. Runs after the
 * security chain (subject available) and lazily activates INVITED → ACTIVE on the first real API hit.
 *
 * <p>{@code GET /api/v1/me} is the one lenient path — unprovisioned users may render onboarding —
 * but it is method-scoped and never lenient for DISABLED accounts: disabled means no access at all.
 *
 * <p>Under an impersonation session this gate evaluates the TARGET, because {@code ImpersonationFilter}
 * swapped the principal at {@code @Order(-2)}. It therefore refuses exactly the accounts it refuses to
 * their owners — and does <em>not</em> activate them: see {@link #decide}.
 */
@Component
@Order(1)
@ConditionalOnProperty(name = "app.provisioning.gate-enabled", havingValue = "true", matchIfMissing = true)
class ProvisioningGateFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningGateFilter.class);
    private static final String ME_PATH = "/api/v1/me";

    private final UserAccessService access;
    private final CurrentUserProvider currentUserProvider;
    private final EnvelopeErrorWriter errorWriter;

    ProvisioningGateFilter(UserAccessService access, CurrentUserProvider currentUserProvider,
            EnvelopeErrorWriter errorWriter) {
        this.access = access;
        this.currentUserProvider = currentUserProvider;
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !RequestPaths.of(request).startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CurrentUser user = currentUserProvider.currentUser().orElse(null);
        if (user == null) {
            chain.doFilter(request, response); // unauthenticated — let the security chain handle it
            return;
        }
        String subject = user.subject();
        Decision decision;
        try {
            decision = decide(request, user);
        } catch (RuntimeException ex) {
            // A filter exception would bypass GlobalExceptionHandler and produce a non-envelope 500;
            // render the envelope ourselves (never leak the exception to the client).
            log.error("Provisioning gate failed for subject {}: {}", subject, ex.toString(), ex);
            errorWriter.write(request, response, ErrorCode.INTERNAL_ERROR,
                    "The request could not be processed.", null);
            return;
        }
        switch (decision) {
            case ALLOWED -> chain.doFilter(request, response);
            case NOT_PROVISIONED -> errorWriter.write(request, response, ErrorCode.ACCOUNT_NOT_PROVISIONED,
                    "Your account has not been provisioned. Contact an administrator.", null);
            case DISABLED -> errorWriter.write(request, response, ErrorCode.ACCOUNT_DISABLED,
                    "Your account has been disabled.", null);
        }
    }

    /**
     * The one place {@code authorize} (which writes) is chosen over {@code peek} (which does not).
     *
     * <p>Lazy INVITED → ACTIVE means "the person finally showed up", and an operator wearing them is
     * not them showing up: activating here would flip {@code status}, stamp {@code activated_at} and
     * publish {@code UserActivated} on the strength of somebody else's read — a durable write from a
     * session that may well be READ_ONLY, stamped {@code updated_by = <the target>} because the
     * effective subject is theirs, and with no {@code audit_log} row naming the operator anywhere.
     * The "never signed in" signal any invite-expiry logic keys on would be destroyed silently.
     * {@code peek} returns the identical DISABLED / deleted / NOT_PROVISIONED answers without it.
     */
    private Decision decide(HttpServletRequest request, CurrentUser user) {
        if (isOnboardingRead(request)) {
            // peek(): no lazy activation from /me, and NOT_PROVISIONED may pass (onboarding) —
            // but DISABLED is still a hard stop below.
            Decision peeked = access.peek(user.subject());
            return peeked == Decision.NOT_PROVISIONED ? Decision.ALLOWED : peeked;
        }
        return user.isImpersonated() ? access.peek(user.subject()) : access.authorize(user.subject());
    }

    private boolean isOnboardingRead(HttpServletRequest request) {
        return "GET".equals(request.getMethod()) && ME_PATH.equals(RequestPaths.of(request));
    }
}
