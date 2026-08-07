package ug.co.smsone.identity.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Policy for reconciling {@code person} against Keycloak. The {@code enabled} flag itself is read by
 * {@code @ConditionalOnProperty} on {@link IdentityReconciliationJob}.
 *
 * <p>This is the only scheduled job in the system that can REVOKE access, and it decides from a remote
 * system's answer, so its defaults are chosen to fail safe rather than fail tidy. Two of them are load
 * bearing:
 *
 * <p>{@code maxOrphanRatio} is the misconfiguration circuit breaker. Deleting one account is normal;
 * every account looking deleted at once is not a mass resignation, it is a wrong realm name, a wrong
 * base URL, or a service account that lost {@code view-users} — and in each of those the "correct"
 * per-row answer is 404. Without the ratio the job would faithfully disable the entire user base. Above
 * the threshold it disables nothing and logs an error, because being wrong loudly beats being wrong
 * completely.
 *
 * <p>{@code gracePeriod} keeps the job away from people young enough to still be mid-provisioning. That
 * matters more since the ordering inverted: the {@code person} row is now written FIRST and the
 * Keycloak link LAST, so a partly-finished provision is a person with no link — which this job skips
 * rather than orphans, but only because the skip is explicit. The grace period is the second line, and
 * it keeps the job from racing the operator who is retrying the invite.
 */
@ConfigurationProperties(prefix = "app.identity.reconciliation")
record IdentityReconciliationProperties(Action action, Duration gracePeriod,
        double maxOrphanRatio, int batchSize) {

    /** What to do with a row whose Keycloak account is definitively gone. */
    enum Action {
        /** Log and audit only. The safe way to watch the job for a while before letting it act. */
        REPORT,
        /** Mark the row DISABLED. Reversible, and it only makes the listing agree with reality. */
        DISABLE
    }

    IdentityReconciliationProperties {
        if (action == null) {
            action = Action.REPORT;
        }
        if (gracePeriod == null || gracePeriod.isNegative()) {
            gracePeriod = Duration.ofHours(1);
        }
        if (maxOrphanRatio <= 0 || maxOrphanRatio > 1) {
            maxOrphanRatio = 0.10;
        }
        if (batchSize <= 0) {
            batchSize = 500;
        }
    }
}
