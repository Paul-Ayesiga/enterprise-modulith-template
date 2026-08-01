package ug.co.smsone.identity.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Sort;
import org.springframework.data.support.WindowIterator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import ug.co.smsone.identity.ProvisioningStatus;
import ug.co.smsone.identity.internal.IdentityReconciliationProperties.Action;
import ug.co.smsone.shared.audit.AuditLog;

/**
 * Finds {@code app_user} rows whose Keycloak account no longer exists and, optionally, revokes them.
 *
 * <p>Keycloak is the system of record for identity; {@code app_user} is a projection of it. Nothing
 * pushes a deletion from there to here — Keycloak has no outbound hook this application consumes — so
 * without a pull the projection only ever grows, and a deleted account lingers indefinitely as an
 * {@code ACTIVE}-looking row. Two ways that bites: a platform operator can open an impersonation session
 * against a subject that cannot exist, and the listing an auditor reads stops matching who can actually
 * sign in.
 *
 * <p><b>Access is never at risk from the lag itself</b>, and that is what makes a daily pull sufficient
 * rather than negligent. Provisioning is no-JIT and authentication is Keycloak's: an account deleted
 * there can never mint a token again, whatever this table says. This job corrects the RECORD, it does
 * not close a hole.
 *
 * <p>It is nevertheless the only scheduled job that can revoke access, so it is built to be wrong
 * safely — see {@link IdentityReconciliationProperties} for the ratio breaker and the grace period, and
 * {@link KeycloakUserAdminGateway#accountPresence} for why a lookup failure can never be mistaken for a
 * deletion. It ships in {@code REPORT} mode: a fork should watch what it would have done before letting
 * it act.
 *
 * <p>Lives in {@code identity} rather than {@code scheduler} with the purge jobs because it needs the
 * Keycloak gateway and the user repository, both {@code internal} to this module. The scheduler module
 * owns the ShedLock infrastructure, not a monopoly on {@code @Scheduled}.
 */
@Component
@ConditionalOnProperty(name = "app.identity.reconciliation.enabled", havingValue = "true", matchIfMissing = true)
class IdentityReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(IdentityReconciliationJob.class);

    // Well under the PT30M ShedLock lease: a hung-but-answering Keycloak must not run the pass past
    // the lock and into a second instance's concurrent run.
    private static final Duration RUN_DEADLINE = Duration.ofMinutes(25);

    private final UserRepository users;
    private final KeycloakUserAdminGateway keycloak;
    private final IdentityReconciliationProperties properties;
    private final AuditLog auditLog;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    IdentityReconciliationJob(UserRepository users, KeycloakUserAdminGateway keycloak,
            IdentityReconciliationProperties properties, AuditLog auditLog,
            TransactionTemplate transactionTemplate, Clock clock) {
        this.users = users;
        this.keycloak = keycloak;
        this.properties = properties;
        this.auditLog = auditLog;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /** Outcome of one pass, returned so a test can assert on it rather than parse log lines. */
    record Reconciliation(int examined, int present, int orphaned, int unknown, int acted, boolean abandoned) {
    }

    @Scheduled(cron = "${app.scheduler.identity-reconciliation-cron:0 0 2 * * *}")
    @SchedulerLock(name = "identity-reconciliation", lockAtMostFor = "PT30M")
    public void reconcile() {
        run();
    }

    /**
     * Walks the WHOLE candidate set in keyset pages of {@code batch-size} instead of loading it all
     * and truncating: the old head-truncation was unordered, so it re-examined an arbitrary head
     * nightly and could starve the tail forever. The deadline is what bounds a pass now — a slow
     * Keycloak cuts it short loudly, never silently, and the next night starts over.
     */
    Reconciliation run() {
        Instant cutoff = clock.instant().minus(properties.gracePeriod());
        Instant deadline = clock.instant().plus(RUN_DEADLINE);
        // Soft-deleted rows are excluded by @SQLRestriction — an account already erased here needs no
        // reconciling, and re-disabling it would churn the audit trail nightly.
        Sort scan = Sort.by(Sort.Order.asc("provisionedAt"), Sort.Order.asc("id"));
        WindowIterator<User> candidates = WindowIterator.of(position -> users.findBy(
                        (root, query, cb) -> cb.and(
                                cb.notEqual(root.get("status"), ProvisioningStatus.DISABLED),
                                cb.lessThan(root.get("provisionedAt"), cutoff)),
                        q -> q.limit(properties.batchSize()).sortBy(scan).scroll(position)))
                .startingAt(ScrollPosition.keyset());

        List<User> orphaned = new ArrayList<>();
        int present = 0;
        int unknown = 0;
        boolean cutShort = false;
        while (candidates.hasNext()) {
            if (clock.instant().isAfter(deadline)) {
                cutShort = true;
                break;
            }
            User user = candidates.next();
            switch (keycloak.accountPresence(user.getSubject())) {
                case PRESENT -> present++;
                case ABSENT -> orphaned.add(user);
                case UNKNOWN -> unknown++; // never acted on, by construction
            }
        }
        int examined = present + orphaned.size() + unknown;
        if (cutShort) {
            log.warn("Identity reconciliation stopped at its {} deadline after {} accounts — the rest "
                    + "wait for the next run. Is Keycloak slow?", RUN_DEADLINE, examined);
        }
        if (examined == 0) {
            return new Reconciliation(0, 0, 0, 0, 0, false);
        }

        // The circuit breaker. A ratio this high is a configuration fault, not attrition — acting on it
        // would disable the user base on the strength of a wrong realm name.
        double ratio = examined == 0 ? 0 : (double) orphaned.size() / examined;
        if (ratio > properties.maxOrphanRatio()) {
            log.error("Identity reconciliation ABANDONED: {} of {} examined accounts appear deleted in Keycloak "
                            + "({}%, cap {}%). That is a configuration fault, not attrition — check the realm, the "
                            + "base URL and the service account's view-users role. Nothing was changed.",
                    orphaned.size(), examined, Math.round(ratio * 100),
                    Math.round(properties.maxOrphanRatio() * 100));
            return new Reconciliation(examined, present, orphaned.size(), unknown, 0, true);
        }

        int acted = properties.action() == Action.DISABLE ? disable(orphaned) : 0;
        if (!orphaned.isEmpty()) {
            log.warn("Identity reconciliation: {} of {} accounts no longer exist in Keycloak ({} {}); "
                            + "{} lookups inconclusive and skipped.",
                    orphaned.size(), examined, acted,
                    properties.action() == Action.DISABLE ? "disabled" : "reported only", unknown);
        } else {
            log.debug("Identity reconciliation: {} examined, all present ({} inconclusive).", examined, unknown);
        }
        return new Reconciliation(examined, present, orphaned.size(), unknown, acted, false);
    }

    /**
     * One transaction per row, not one for the batch: a single unexpected failure should cost one
     * account's reconciliation, not the whole night's, and each audit row commits with the change it
     * describes.
     */
    private int disable(List<User> orphaned) {
        int disabled = 0;
        RuntimeException first = null;
        for (User user : orphaned) {
            try {
                disableOne(user);
                disabled++;
            } catch (RuntimeException ex) {
                log.error("Could not disable orphaned subject {}: {}", user.getSubject(), ex.toString(), ex);
                if (first == null) {
                    first = ex;
                }
            }
        }
        // §7: isolate, log and rethrow at the end — loud AND complete. Without the rethrow a
        // permanently poisoned row's only symptom is one nightly ERROR line, forever.
        if (first != null) {
            throw first;
        }
        return disabled;
    }

    /**
     * Explicit template, not {@code @Transactional}: this is a self-invocation from a non-transactional
     * method, which never reaches the proxy — the same reason {@link ImpersonationService#open} opens its
     * write this way. The boundary is what makes the row change and the audit row that explains it commit
     * together; without it they are two independent commits and a failure between them leaves a DISABLED
     * account nothing accounts for.
     */
    private void disableOne(User user) {
        transactionTemplate.executeWithoutResult(tx -> {
            User current = users.findBySubject(user.getSubject()).orElse(null);
            if (current == null || current.getStatus() == ProvisioningStatus.DISABLED) {
                return; // ended or already handled between the scan and now
            }
            current.disable();
            users.save(current);
            // actor is null: nobody did this, a scheduled comparison did. Recording an operator here would
            // put a name on a decision no human made.
            auditLog.record("identity.user_disabled_by_reconciliation", null, current.getSubject(),
                    "status=" + ProvisioningStatus.ACTIVE, "status=" + ProvisioningStatus.DISABLED);
        });
    }
}
