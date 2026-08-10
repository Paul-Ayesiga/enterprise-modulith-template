package ug.co.smsone.shared.cache;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The hard ceiling on serving a last-known platform answer while the platform database is unreachable
 * (ADR 0011 §2, {@code app.tenancy.identity-stale.ceiling}). One number for every stale-capable read —
 * {@code person-by-subject}, {@code org-by-external-id}, {@code plan-catalog} — because the promise it
 * backs is singular: <em>a revoked identity is honored within fifteen minutes</em>, measured per entry
 * from its last successful authoritative refresh, never from the start of the outage.
 *
 * <p>Lower is legal (a deployment may tighten the promise); higher fails startup by name, because the
 * ADR's revocation bound is written against PT15M and raising it here would quietly rewrite a decision
 * that section 4.1 says needs the ADR reopened.
 */
@ConfigurationProperties(prefix = "app.tenancy.identity-stale")
public record IdentityStaleProperties(@DefaultValue("PT15M") Duration ceiling) {

    /** ADR 0011 §4.1's bound. Not tunable upward — see the class note. */
    private static final Duration ADR_CEILING = Duration.ofMinutes(15);

    public IdentityStaleProperties {
        if (ceiling == null) {
            ceiling = ADR_CEILING;
        }
        if (ceiling.isNegative() || ceiling.isZero()) {
            throw new IllegalArgumentException("app.tenancy.identity-stale.ceiling must be positive; "
                    + ceiling + " would mean a platform outage denies every request instantly, which is"
                    + " the availability the stale window exists to buy (ADR 0011 §2)");
        }
        if (ceiling.compareTo(ADR_CEILING) > 0) {
            throw new IllegalArgumentException("app.tenancy.identity-stale.ceiling is " + ceiling
                    + " but ADR 0011 §2 promises that a revoked identity is honored within " + ADR_CEILING
                    + " — raising this knob would silently rewrite that promise. Reopen the ADR instead.");
        }
    }
}
