package ug.co.smsone.access.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ug.co.smsone.shared.error.ErrorCode;
import ug.co.smsone.shared.security.CurrentUser;
import ug.co.smsone.shared.security.CurrentUserProvider;
import ug.co.smsone.shared.web.EnvelopeErrorWriter;
import ug.co.smsone.shared.web.ForwardedClientIp;
import ug.co.smsone.shared.web.RequestPaths;

/**
 * Enforces an organization's security policy on its org-scoped requests, AFTER authentication and
 * the caller's active-org resolution (order 3: past impersonation −2, org-MDC −1, rate-limit 0,
 * idempotency 1, provisioning gate 2). Only acts when the URL's org matches the caller's active
 * org — a mismatch is RBAC's 403 to give, not ours. A denial is a distinct 403 whose detail NAMES
 * the policy (IP allowlist / trusted device / session age) so it never reads as a permission bug,
 * and is counted. No policy row, or an absent field, means the platform default (open) applies.
 * {@code require_mfa} holds human JWT sessions to a multi-factor {@code amr} claim; machine
 * principals (API keys, impersonation exchanges) are exempt by construction.
 * The device fingerprint header doubles as the throttled last-seen stamp.
 */
@Component
@Order(3)
public class OrgPolicyEnforcementFilter extends OncePerRequestFilter {

    public static final String DEVICE_HEADER = "X-Device-Id";
    private static final Pattern ORG_PATH = Pattern.compile("^/api/v1/orgs/([0-9a-fA-F-]{36})(/.*)?$");
    private static final java.time.Duration TOUCH_THROTTLE = java.time.Duration.ofMinutes(1);

    private final OrgSecurityPolicyRepository policies;
    private final DeviceService devices;
    private final CurrentUserProvider currentUser;
    private final EnvelopeErrorWriter errorWriter;
    private final MeterRegistry meters;
    private final Clock clock;
    private final ForwardedClientIp forwardedClientIp;

    public OrgPolicyEnforcementFilter(OrgSecurityPolicyRepository policies, DeviceService devices,
            CurrentUserProvider currentUser, EnvelopeErrorWriter errorWriter, MeterRegistry meters, Clock clock,
            ForwardedClientIp forwardedClientIp) {
        this.policies = policies;
        this.devices = devices;
        this.currentUser = currentUser;
        this.errorWriter = errorWriter;
        this.meters = meters;
        this.clock = clock;
        this.forwardedClientIp = forwardedClientIp;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        Matcher match = ORG_PATH.matcher(RequestPaths.of(request));
        CurrentUser caller = match.matches() ? currentUser.currentUser().orElse(null) : null;
        // Compared as UUIDs, not as strings. The path segment is bound as a UUID downstream, so an
        // upper-case rendering of the caller's own org authorizes normally — and a rendering
        // comparison here would then step aside and skip the policy entirely for that request.
        if (caller == null || caller.organizationId() == null
                || !caller.organizationId().equals(pathOrgId(match.group(1)))) {
            chain.doFilter(request, response); // not an org call we own, or scope mismatch → RBAC decides
            return;
        }
        // Recovery hatch: the policy's OWN management endpoint is never gated by the policy —
        // otherwise an allowlist (or trusted-device rule) that excludes you would lock you out of
        // even fixing it. org:update still guards these; only the policy filter steps aside.
        String rest = match.group(2);
        if (rest != null && rest.startsWith("/security-policy")) {
            chain.doFilter(request, response);
            return;
        }
        OrgSecurityPolicy policy = policies.findByOrgId(caller.organizationId()).orElse(null);
        if (policy != null) {
            String denial = evaluate(policy, caller, request);
            if (denial != null) {
                Counter.builder("smsone.securitypolicy.denied")
                        .description("Requests refused by an org security policy, by rule")
                        .tag("rule", denial)
                        .register(meters)
                        .increment();
                errorWriter.write(request, response, ErrorCode.FORBIDDEN,
                        "Blocked by your organization's security policy (" + denial + ").", null);
                return;
            }
        }
        stampDevice(caller, request);
        chain.doFilter(request, response);
    }

    /** @return the violated rule name, or null when the request satisfies the policy. */
    private String evaluate(OrgSecurityPolicy policy, CurrentUser caller, HttpServletRequest request) {
        // The judged address honors app.http.trusted-proxy-hops — behind the gateway/ingress the raw
        // socket peer is OUR proxy, and an allowlist judging it would block everyone or no one.
        if (policy.getIpAllowlist() != null && !policy.getIpAllowlist().isBlank()
                && !CidrMatcher.matchesAny(policy.getIpAllowlist(), forwardedClientIp.clientIp(request))) {
            return "ip-allowlist";
        }
        if (policy.getSessionMaxAgeSeconds() != null) {
            Instant issuedAt = issuedAt();
            if (issuedAt != null
                    && issuedAt.plusSeconds(policy.getSessionMaxAgeSeconds()).isBefore(clock.instant())) {
                return "session-max-age";
            }
        }
        if (policy.isRequireMfa() && lacksMfa()) {
            return "mfa";
        }
        if (policy.isRequireTrustedDevice()) {
            String fingerprint = request.getHeader(DEVICE_HEADER);
            // A machine has no person and therefore no registered device, so it can never satisfy
            // this rule — the same answer it always got, now stated instead of falling out of a
            // subject string that matched no row. An org requiring trusted devices excludes its
            // API keys from org-scoped calls; that is the rule doing its job, not a gap.
            // The org is passed explicitly since V51: trust is a grant BY an organization, so the only
            // grant that can satisfy this org's policy is this org's own. Before, one global boolean on
            // the device meant a blessing from any org — including one the caller created themselves —
            // satisfied every org's rule for that person.
            if (caller.personId() == null || fingerprint == null || fingerprint.isBlank()
                    || !devices.isTrusted(caller.organizationId(), caller.personId(), fingerprint.trim())) {
                return "trusted-device";
            }
        }
        return null;
    }

    private void stampDevice(CurrentUser caller, HttpServletRequest request) {
        String fingerprint = request.getHeader(DEVICE_HEADER);
        if (caller.personId() != null && fingerprint != null && !fingerprint.isBlank()) {
            devices.stampLastSeen(caller.personId(), fingerprint.trim(),
                    clock.instant(), clock.instant().minus(TOUCH_THROTTLE));
        }
    }

    /** The URL's org id, or null when the segment is not a UUID (the regex admits the shape only). */
    private static java.util.UUID pathOrgId(String segment) {
        try {
            return java.util.UUID.fromString(segment);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * The token's {@code amr} claim (RFC 8176) must name a second factor. Only HUMAN sessions are
     * held to it: an API key is a machine credential with no MFA to have, and an impersonated
     * principal was minted by a platform admin whose own session already passed the platform's
     * rules — both carry no JWT here and are exempt by construction.
     */
    private static boolean lacksMfa() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwt)) {
            return false;
        }
        java.util.List<String> amr = jwt.getToken().getClaimAsStringList("amr");
        return amr == null || amr.stream().map(String::toLowerCase)
                .noneMatch(java.util.Set.of("otp", "totp", "mfa", "hwk", "webauthn", "swk")::contains);
    }

    private static Instant issuedAt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            Jwt token = jwt.getToken();
            return token.getIssuedAt();
        }
        return null; // API-key / impersonated principals carry no iat — session age does not apply
    }
}
