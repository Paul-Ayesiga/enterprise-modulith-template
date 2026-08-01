package ug.co.smsone.identity.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Session lifetime policy. The {@code app.impersonation.enabled} kill switch is not bound here — the
 * controller and the filter each read it directly, because the filter lives in {@code shared} and this
 * record is package-private to {@code identity}. Switching it off makes both answer 403 naming the
 * switch: the feature refuses, it does not disappear, and it never silently ignores the header.
 *
 * <p>A request may ask for a shorter TTL than the default but never a longer one than {@code maxTtl},
 * and an over-cap request is <b>rejected (422), not silently clamped</b>: an operator who asked for two
 * hours and quietly got thirty minutes discovers it as an unexplained denial in the middle of an
 * investigation, whereas a 422 naming the cap is something they can act on immediately. Both bounds
 * are enforced server-side — the client's number is a request, never a grant.
 *
 * <p>A default above the cap would mean every plain request mints an over-cap session, so it fails at
 * startup instead of at 4am.
 */
@ConfigurationProperties(prefix = "app.impersonation")
record ImpersonationProperties(Duration defaultTtl, Duration maxTtl) {

    ImpersonationProperties {
        if (defaultTtl == null || defaultTtl.isZero() || defaultTtl.isNegative()) {
            defaultTtl = Duration.ofMinutes(15);
        }
        if (maxTtl == null || maxTtl.isZero() || maxTtl.isNegative()) {
            maxTtl = Duration.ofMinutes(30);
        }
        if (defaultTtl.compareTo(maxTtl) > 0) {
            throw new IllegalArgumentException(
                    "app.impersonation.default-ttl (" + defaultTtl + ") must not exceed max-ttl (" + maxTtl
                            + ") — every default session would be minted over the cap.");
        }
    }
}
