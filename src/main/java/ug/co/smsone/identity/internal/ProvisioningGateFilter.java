package ug.co.smsone.identity.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
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
 * security chain (subject available), whitelists {@code GET /api/v1/me} (so INVITED users can render
 * onboarding), and lazily activates INVITED → ACTIVE on the first real API hit.
 */
@Component
@Order(1)
@ConditionalOnProperty(name = "app.provisioning.gate-enabled", havingValue = "true", matchIfMissing = true)
class ProvisioningGateFilter extends OncePerRequestFilter {

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
        String path = path(request);
        return !path.startsWith("/api/") || path.equals(ME_PATH);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String subject = currentUserProvider.currentUser().map(CurrentUser::subject).orElse(null);
        if (subject == null) {
            chain.doFilter(request, response); // unauthenticated — let the security chain handle it
            return;
        }
        Decision decision = access.authorize(subject);
        switch (decision) {
            case ALLOWED -> chain.doFilter(request, response);
            case NOT_PROVISIONED -> errorWriter.write(request, response, ErrorCode.ACCOUNT_NOT_PROVISIONED,
                    "Your account has not been provisioned. Contact an administrator.", null);
            case DISABLED -> errorWriter.write(request, response, ErrorCode.ACCOUNT_DISABLED,
                    "Your account has been disabled.", null);
        }
    }

    private static String path(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        return (servletPath != null && !servletPath.isEmpty()) ? servletPath : request.getRequestURI();
    }
}
