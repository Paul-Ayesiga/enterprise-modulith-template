package ug.co.smsone.maintenance.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ug.co.smsone.shared.error.ErrorCode;
import ug.co.smsone.shared.security.CurrentUser;
import ug.co.smsone.shared.security.CurrentUserProvider;
import ug.co.smsone.shared.web.EnvelopeErrorWriter;
import ug.co.smsone.shared.web.RequestPaths;

/**
 * A RESTRICT maintenance window in effect answers org-scoped WRITES with 503 + Retry-After; reads
 * always pass, and ANNOUNCE windows never block. Order 4 — after the security-policy filter (3),
 * so an authenticated caller with a resolved active org is what we gate. The window's own
 * management endpoints are never gated (you can always cancel a window), like the policy filter's
 * recovery hatch. Idempotent GET/HEAD/OPTIONS and non-org paths pass untouched.
 */
@Component
@Order(4)
public class MaintenanceFilter extends OncePerRequestFilter {

    private static final Pattern ORG_PATH = Pattern.compile("^/api/v1/orgs/([0-9a-fA-F-]{36})(/.*)?$");
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final MaintenanceWindowRepository windows;
    private final CurrentUserProvider currentUser;
    private final EnvelopeErrorWriter errorWriter;
    private final Clock clock;

    public MaintenanceFilter(MaintenanceWindowRepository windows, CurrentUserProvider currentUser,
            EnvelopeErrorWriter errorWriter, Clock clock) {
        this.windows = windows;
        this.currentUser = currentUser;
        this.errorWriter = errorWriter;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (!WRITE_METHODS.contains(request.getMethod())) {
            chain.doFilter(request, response); // reads always pass
            return;
        }
        Matcher match = ORG_PATH.matcher(RequestPaths.of(request));
        if (!match.matches() || (match.group(2) != null && match.group(2).startsWith("/maintenance"))) {
            chain.doFilter(request, response); // not an org write, or the window's own controls
            return;
        }
        CurrentUser caller = currentUser.currentUser().orElse(null);
        UUID orgId = caller == null ? null : caller.organizationId();
        List<MaintenanceWindow> active = windows.activeFor(clock.instant(), orgId);
        MaintenanceWindow restricting = active.stream()
                .filter(MaintenanceWindow::restricts).findFirst().orElse(null);
        if (restricting == null) {
            chain.doFilter(request, response);
            return;
        }
        long retryAfter = Math.max(1, java.time.Duration.between(clock.instant(),
                restricting.getEndsAt()).toSeconds());
        response.setHeader("Retry-After", String.valueOf(retryAfter));
        errorWriter.write(request, response, ErrorCode.SERVICE_UNAVAILABLE,
                "Undergoing maintenance until " + restricting.getEndsAt() + ": " + restricting.getMessage(), null);
    }
}
