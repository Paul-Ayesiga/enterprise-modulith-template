package ug.co.smsone.signup.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.notification.NotificationRequest;
import ug.co.smsone.notification.Notifications;
import ug.co.smsone.notification.Recipient;
import ug.co.smsone.organization.Organizations;
import ug.co.smsone.organization.ProvisionedOrganization;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.shared.error.ConflictException;
import ug.co.smsone.shared.error.ForbiddenException;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.web.ApiSource;

/**
 * The signup handshake. {@code request} is deliberately enumeration-safe: it answers the same 202
 * whether or not the email is known anywhere, stores only the token's SHA-256, and supersedes any
 * pending request for the same address. {@code verify} spends the single-use token and then runs the
 * STANDARD provisioning path through the {@link Organizations} port — so the owner invite,
 * {@code OrganizationRegistered}, trial-on-signup, and billing auto-provision all behave exactly as
 * if a platform admin had created the org. Alias collisions retry with a numeric suffix.
 */
@Service
class SignupService {

    private static final Logger log = LoggerFactory.getLogger(SignupService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_ALIAS_ATTEMPTS = 6;

    private final SignupRepository requests;
    private final Organizations organizations;
    private final Notifications notifications;
    private final SignupProperties properties;
    private final AuditLog auditLog;
    private final Clock clock;

    SignupService(SignupRepository requests, Organizations organizations, Notifications notifications,
            SignupProperties properties, AuditLog auditLog, Clock clock) {
        this.requests = requests;
        this.organizations = organizations;
        this.notifications = notifications;
        this.properties = properties;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    @Transactional
    void request(String organizationName, String email, String givenName, String familyName) {
        requireEnabled();
        String name = organizationName == null ? "" : organizationName.trim();
        if (name.length() < 2 || name.length() > 80) {
            throw new ValidationException("organizationName is required (2–80 characters).",
                    ApiSource.pointer("/data/attributes/organizationName"));
        }
        String normalizedEmail = email == null ? "" : email.trim().toLowerCase();
        if (!normalizedEmail.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw new ValidationException("A valid email is required.",
                    ApiSource.pointer("/data/attributes/email"));
        }
        // Resend cooldown. Without it this endpoint is an email cannon: it is unauthenticated by
        // nature, and every call dispatched a fresh verification mail, so anyone could point it at a
        // victim's address and send them a thousand messages — harassing them and burning our sending
        // reputation. The shared per-IP limiter does not help, because the abuse is per-RECIPIENT.
        //
        // Returning early here rather than throwing is deliberate: this endpoint is enumeration-safe
        // and must answer the same 202 whether or not the address is known or pending. A 409 "already
        // requested" would turn it into an oracle for which addresses have accounts.
        Instant now = clock.instant();
        var live = requests.findFirstByEmailAndStatusOrderByCreatedAtDesc(
                normalizedEmail, SignupRequest.PENDING);
        if (live.isPresent()
                && live.get().getCreatedAt().isAfter(now.minus(properties.resendCooldown()))) {
            // Deliberately does not log the address — this line would otherwise become the enumeration
            // oracle the response refuses to be.
            log.info("Signup re-requested inside the {} cooldown; no verification email sent",
                    properties.resendCooldown());
            auditLog.record("signup.throttled", null, normalizedEmail, null,
                    "cooldown=" + properties.resendCooldown());
            return;
        }

        // One live handshake per address: a new request supersedes the pending one.
        requests.deleteByEmailAndStatus(normalizedEmail, SignupRequest.PENDING);

        byte[] tokenBytes = new byte[32];
        RANDOM.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        requests.save(SignupRequest.pending(normalizedEmail, name, blankToNull(givenName),
                blankToNull(familyName), sha256(token), clock.instant().plus(properties.tokenTtl()),
                clock.instant()));

        String link = properties.verifyUrl() + "?token=" + token;
        notifications.dispatch(NotificationRequest.of(
                "Verify your email to create '" + name + "'",
                "You asked to create the organization '" + name + "'.\n\n"
                        + "Confirm your email to continue:\n" + link + "\n\n"
                        + "The link is valid for " + properties.tokenTtl().toHours() + " hours. "
                        + "If you didn't request this, ignore this message — nothing is created "
                        + "without this confirmation.",
                List.of(Recipient.email(normalizedEmail))));
        log.info("Signup requested for '{}' — verification email queued", name);
    }

    @Transactional
    SignupRequest verify(String token) {
        requireEnabled();
        if (token == null || token.isBlank()) {
            throw new ValidationException("token is required.", ApiSource.parameter("token"));
        }
        SignupRequest request = requests
                .findByTokenHashAndStatus(sha256(token.trim()), SignupRequest.PENDING)
                .orElseThrow(SignupService::invalidToken);
        if (request.expired(clock.instant())) {
            throw invalidToken();
        }
        // Both ids come back from the ONE call that created them, and both are written here. V42's
        // point: a completed request used to record no link to the person it produced, so the
        // request -> person relationship was recoverable only by matching the email string back.
        ProvisionedOrganization created = createWithAliasRetry(request);
        request.completed(created.organizationId(), created.ownerPersonId(), clock.instant());
        requests.save(request);
        auditLog.record("signup.completed", created.organizationId(), request.getEmail(), null,
                "org=" + request.getOrgName());
        return request;
    }

    /** The standard path throws 409 on a taken alias; suffix and retry a bounded number of times. */
    private ProvisionedOrganization createWithAliasRetry(SignupRequest request) {
        String base = slug(request.getOrgName());
        for (int attempt = 0; attempt < MAX_ALIAS_ATTEMPTS; attempt++) {
            String candidate = attempt == 0 ? base : base + "-" + (attempt + 1);
            try {
                return organizations.create(candidate, request.getOrgName(), request.getEmail(),
                        request.getGivenName(), request.getFamilyName());
            } catch (ConflictException taken) {
                // try the next suffix
            }
        }
        throw new ConflictException("Could not find a free alias for '" + request.getOrgName()
                + "' — try a more distinctive organization name.");
    }

    static String slug(String name) {
        String slug = name.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (slug.isBlank()) {
            slug = "org";
        }
        return slug.length() > 30 ? slug.substring(0, 30).replaceAll("-+$", "") : slug;
    }

    private void requireEnabled() {
        if (!Boolean.TRUE.equals(properties.enabled())) {
            throw new ForbiddenException(
                    "Self-service signup is disabled on this platform (SIGNUP_ENABLED).");
        }
    }

    private static ValidationException invalidToken() {
        // One generic message for absent, spent, and expired — a probe learns nothing.
        return new ValidationException("This verification link is invalid or has expired — start a new signup.",
                ApiSource.parameter("token"));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
