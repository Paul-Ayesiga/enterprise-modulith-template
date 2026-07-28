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

/**
 * No-JIT access gate: an authenticated request to {@code /api/**} requires a provisioned
 * {@code app_user}, else 403 — so a valid Keycloak JWT is not, by itself, access. Runs after the
 * security chain (subject available) and lazily activates INVITED → ACTIVE on the first real API hit.
 *
 * <p>{@code GET /api/v1/me} is the one lenient path — unprovisioned users may render onboarding —
 * but it is method-scoped and never lenient for DISABLED accounts: disabled means no access at all.
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
        return !path(request).startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String subject = currentUserProvider.currentUser().map(CurrentUser::subject).orElse(null);
        if (subject == null) {
            chain.doFilter(request, response); // unauthenticated — let the security chain handle it
            return;
        }
        Decision decision;
        try {
            if (isOnboardingRead(request)) {
                // peek(): no lazy activation from /me, and NOT_PROVISIONED may pass (onboarding) —
                // but DISABLED is still a hard stop below.
                Decision peeked = access.peek(subject);
                decision = peeked == Decision.NOT_PROVISIONED ? Decision.ALLOWED : peeked;
            } else {
                decision = access.authorize(subject);
            }
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

    private boolean isOnboardingRead(HttpServletRequest request) {
        return "GET".equals(request.getMethod()) && ME_PATH.equals(path(request));
    }

    private static String path(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        return (servletPath != null && !servletPath.isEmpty()) ? servletPath : request.getRequestURI();
    }
}
