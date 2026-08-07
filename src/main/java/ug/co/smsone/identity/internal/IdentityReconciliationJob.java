package ug.co.smsone.identity.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
 * Finds people whose Keycloak account no longer exists and, optionally, revokes their access.
 *
 * <p>Keycloak authenticates; this platform authorizes. Nothing pushes a deletion from there to here —
 * Keycloak has no outbound hook this application consumes — so without a pull the {@code
 * external_identity} link only ever accumulates, and a deleted account lingers indefinitely behind an
 * {@code ACTIVE}-looking person. Two ways that bites: a platform operator can open an impersonation
 * session against someone who cannot sign in, and the listing an auditor reads stops matching who
 * actually has access.
 *
 * <p><b>Access is never at risk from the lag itself</b>, and that is what makes a daily pull sufficient
 * rather than negligent. Provisioning is no-JIT and authentication is Keycloak's: an account deleted
 * there can never mint a token again, whatever these tables say. This job corrects the RECORD, it does
 * not close a hole.
 *
 * <p>It is nevertheless the only scheduled job that can revoke access, so it is built to be wrong
 * safely — see {@link IdentityReconciliationProperties} for the ratio breaker and the grace period, and
 * {@link KeycloakUserAdminGateway#accountPresence} for why a lookup failure can never be mistaken for a
 * deletion. It ships in {@code REPORT} mode: a fork should watch what it would have done before letting
 * it act.
 *
 * <p>Lives in {@code identity} rather than {@code scheduler} with the purge jobs because it needs the
 * Keycloak gateway and this module's repositories, all {@code internal}. The scheduler module owns the
 * ShedLock infrastructure, not a monopoly on {@code @Scheduled}.
 */
@Component
@ConditionalOnProperty(name = "app.identity.reconciliation.enabled", havingValue = "true", matchIfMissing = true)
class IdentityReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(IdentityReconciliationJob.class);

    // Well under the PT30M ShedLock lease: a hung-but-answering Keycloak must not run the pass past
    // the lock and into a second instance's concurrent run.
    private static final Duration RUN_DEADLINE = Duration.ofMinutes(25);

    private final PersonRepository persons;
    private final PersonResolver resolver;
    private final KeycloakUserAdminGateway keycloak;
    private final IdentityReconciliationProperties properties;
    private final AuditLog auditLog;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    IdentityReconciliationJob(PersonRepository persons, PersonResolver resolver,
            KeycloakUserAdminGateway keycloak, IdentityReconciliationProperties properties, AuditLog auditLog,
            TransactionTemplate transactionTemplate, Clock clock) {
        this.persons = persons;
        this.resolver = resolver;
        this.keycloak = keycloak;
        this.properties = properties;
        this.auditLog = auditLog;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /**
     * Outcome of one pass, returned so a test can assert on it rather than parse log lines.
     * {@code unlinked} counts people this job has no opinion about — see {@link #run}.
     */
    record Reconciliation(int examined, int present, int orphaned, int unknown, int unlinked, int acted,
            boolean abandoned) {
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
     *
     * <p>The scan is over {@code person} in {@code (invited_at, id)} order, which is precisely what
     * {@code idx_person_scan} exists for. A person with no Keycloak link is counted and skipped rather
     * than treated as orphaned: they may be federated only elsewhere, or caught between the person row
     * and the link during provisioning, and in neither case has Keycloak deleted anything.
     */
    Reconciliation run() {
        Instant cutoff = clock.instant().minus(properties.gracePeriod());
        Instant deadline = clock.instant().plus(RUN_DEADLINE);
        // Soft-deleted rows are excluded by @SQLRestriction — an account already erased here needs no
        // reconciling, and re-disabling it would churn the audit trail nightly.
        Sort scan = Sort.by(Sort.Order.asc("invitedAt"), Sort.Order.asc("id"));
        WindowIterator<Person> candidates = WindowIterator.of(position -> persons.findBy(
                        (root, query, cb) -> cb.and(
                                cb.notEqual(root.get("status"), ProvisioningStatus.DISABLED),
                                cb.lessThan(root.get("invitedAt"), cutoff)),
                        q -> q.limit(properties.batchSize()).sortBy(scan).scroll(position)))
                .startingAt(ScrollPosition.keyset());

        List<UUID> orphaned = new ArrayList<>();
        int present = 0;
        int unknown = 0;
        int unlinked = 0;
        boolean cutShort = false;
        while (candidates.hasNext()) {
            if (clock.instant().isAfter(deadline)) {
                cutShort = true;
                break;
            }
            Person person = candidates.next();
            // One lookup per person rather than a batched join: the remote round-trip below dominates
            // this by orders of magnitude, and keeping the pairing here is what lets the skip above be
            // a visible, counted outcome instead of a row silently missing from a join.
            String subject = resolver.keycloakSubjectOf(person.getId()).orElse(null);
            if (subject == null) {
                unlinked++;
                continue;
            }
            switch (keycloak.accountPresence(subject)) {
                case PRESENT -> present++;
                case ABSENT -> orphaned.add(person.getId());
                case UNKNOWN -> unknown++; // never acted on, by construction
            }
        }
        int examined = present + orphaned.size() + unknown;
        if (cutShort) {
            log.warn("Identity reconciliation stopped at its {} deadline after {} accounts — the rest "
                    + "wait for the next run. Is Keycloak slow?", RUN_DEADLINE, examined);
        }
        if (examined == 0) {
            return new Reconciliation(0, 0, 0, 0, unlinked, 0, false);
        }

        // The circuit breaker. A ratio this high is a configuration fault, not attrition — acting on it
        // would disable the user base on the strength of a wrong realm name.
        double ratio = (double) orphaned.size() / examined;
        if (ratio > properties.maxOrphanRatio()) {
            log.error("Identity reconciliation ABANDONED: {} of {} examined accounts appear deleted in Keycloak "
                            + "({}%, cap {}%). That is a configuration fault, not attrition — check the realm, the "
                            + "base URL and the service account's view-users role. Nothing was changed.",
                    orphaned.size(), examined, Math.round(ratio * 100),
                    Math.round(properties.maxOrphanRatio() * 100));
            return new Reconciliation(examined, present, orphaned.size(), unknown, unlinked, 0, true);
        }

        int acted = properties.action() == Action.DISABLE ? disable(orphaned) : 0;
        if (!orphaned.isEmpty()) {
            log.warn("Identity reconciliation: {} of {} accounts no longer exist in Keycloak ({} {}); "
                            + "{} lookups inconclusive and skipped, {} people hold no Keycloak link at all.",
                    orphaned.size(), examined, acted,
                    properties.action() == Action.DISABLE ? "disabled" : "reported only", unknown, unlinked);
        } else {
            log.debug("Identity reconciliation: {} examined, all present ({} inconclusive, {} unlinked).",
                    examined, unknown, unlinked);
        }
        return new Reconciliation(examined, present, orphaned.size(), unknown, unlinked, acted, false);
    }

    /**
     * One transaction per person, not one for the batch: a single unexpected failure should cost one
     * account's reconciliation, not the whole night's, and each audit row commits with the change it
     * describes.
     */
    private int disable(List<UUID> orphaned) {
        int disabled = 0;
        RuntimeException first = null;
        for (UUID personId : orphaned) {
            try {
                disableOne(personId);
                disabled++;
            } catch (RuntimeException ex) {
                log.error("Could not disable orphaned person {}: {}", personId, ex.toString(), ex);
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
    private void disableOne(UUID personId) {
        transactionTemplate.executeWithoutResult(tx -> {
            Person current = persons.findById(personId).orElse(null);
            if (current == null || current.getStatus() == ProvisioningStatus.DISABLED) {
                return; // ended or already handled between the scan and now
            }
            current.disable(clock.instant());
            persons.save(current);
            // actor is null: nobody did this, a scheduled comparison did. Recording an operator here would
            // put a name on a decision no human made.
            auditLog.record("identity.person_disabled_by_reconciliation", null, personId.toString(),
                    "status=" + ProvisioningStatus.ACTIVE, "status=" + ProvisioningStatus.DISABLED);
        });
    }
}
