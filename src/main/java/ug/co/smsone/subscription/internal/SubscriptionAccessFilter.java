package ug.co.smsone.subscription.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
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
 * A PAUSED subscription (its trial lapsed unpaid) makes the org READ-ONLY: org-scoped WRITES answer
 * 402 Payment Required, reads always pass. Order 5 — after the maintenance filter (4), so we gate an
 * authenticated caller with a resolved active org. The org's own billing surface ({@code /billing/**})
 * is the recovery hatch — a paused tenant can still add a payment method to un-pause. TRIALING and
 * ACTIVE never block; only PAUSED does (PAST_DUE stays a billing-side concern, not a lockout).
 */
@Component
@Order(5)
public class SubscriptionAccessFilter extends OncePerRequestFilter {

    private static final Pattern ORG_PATH = Pattern.compile("^/api/v1/orgs/([0-9a-fA-F-]{36})(/.*)?$");
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final OrgSubscriptionRepository subscriptions;
    private final CurrentUserProvider currentUser;
    private final EnvelopeErrorWriter errorWriter;

    public SubscriptionAccessFilter(OrgSubscriptionRepository subscriptions,
            CurrentUserProvider currentUser, EnvelopeErrorWriter errorWriter) {
        this.subscriptions = subscriptions;
        this.currentUser = currentUser;
        this.errorWriter = errorWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (!WRITE_METHODS.contains(request.getMethod())) {
            chain.doFilter(request, response); // reads always pass
            return;
        }
        Matcher match = ORG_PATH.matcher(RequestPaths.of(request));
        if (!match.matches() || (match.group(2) != null && match.group(2).startsWith("/billing"))) {
            chain.doFilter(request, response); // not an org write, or the pay-to-recover hatch
            return;
        }
        CurrentUser caller = currentUser.currentUser().orElse(null);
        UUID orgId = caller == null ? null : caller.organizationId();
        if (orgId == null) {
            chain.doFilter(request, response); // unauthenticated / no active org — authz handles it
            return;
        }
        if (subscriptions.statusOf(orgId).orElse(null) == OrgSubscription.Status.PAUSED) {
            errorWriter.write(request, response, ErrorCode.SUBSCRIPTION_PAUSED,
                    "This organization's subscription is paused (its trial lapsed). It is read-only "
                            + "until a plan is assigned or a payment is made.", null);
            return;
        }
        chain.doFilter(request, response);
    }
}
