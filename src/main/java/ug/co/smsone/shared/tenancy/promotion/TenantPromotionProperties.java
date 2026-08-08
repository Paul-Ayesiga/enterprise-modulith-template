package ug.co.smsone.shared.tenancy.promotion;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.tenancy.promotion.*} — the three numbers a promotion is bounded by (ADR 0010 §8 Q1, §6).
 *
 * <p>Defaulted here as well as in {@code application.yaml} so a slice that never loads the yaml still
 * gets the ADR's numbers rather than a null. Every default is the ADR's recommendation, and each one is
 * a number an operator can move — which is the point of them being configuration at all: §8 Q1 says the
 * ceiling is "<em>the one number in the document I would most want re-measured before it is
 * load-bearing</em>", and a constant would make re-measuring it a deploy.
 *
 * @param maxSilos the silo ceiling per database — see {@link #DEFAULT_MAX_SILOS} for the derivation,
 *     which is also what an operator hitting the refusal is shown
 * @param freeze how long a promotion may hold a tenant's writes before its own freeze lapses. The
 *     budget, not the expected duration: a promotion that outruns it is aborted, because a freeze that
 *     silently extended itself would be a pause with no bound at all
 * @param drain how long to wait after the freeze is taken before reading the source, so requests that
 *     were already in flight finish. It bounds the in-flight window; it does not close it — the
 *     fingerprint comparison is what makes the copy correct (see {@code TenantRows.fingerprint})
 */
@ConfigurationProperties(prefix = "app.tenancy.promotion")
record TenantPromotionProperties(Integer maxSilos, Duration freeze, Duration drain) {

    /**
     * <strong>200 silos per database, and here is the arithmetic (ADR 0010 §8 Q1).</strong>
     *
     * <p>A promoted tenant is a schema, and a schema is a branch in every cross-tenant fan-out query
     * the platform still runs — the support ticket queue's {@code pageForQueue}, {@code lockBreached},
     * the retention sweeps. Two measured costs scale with the number of branches, and neither of them
     * amortizes:
     *
     * <ul>
     *   <li><strong>Planning: ~0.5–0.66 ms per UNION branch, paid on every execution.</strong> Warm
     *       re-runs pay it again, because a query text that grows with the fleet defeats both pgjdbc's
     *       statement cache and Postgres' plan cache. Measured: 50 branches 12–24 ms, 200 branches
     *       69–100 ms, 500 branches 313–389 ms.</li>
     *   <li><strong>Locks: 2.004 entries per branch, against ~6,400 cluster-wide slots</strong>
     *       ({@code max_locks_per_transaction} 64 × {@code max_connections} 100). Measured: 50 branches
     *       ~102 locks, 200 ~402, 500 ~1,002. Run out and the transaction dies with "out of shared
     *       memory", taking whatever it was doing with it.</li>
     * </ul>
     *
     * <p><strong>At 200 that is ~130 ms of planning and ~403 lock entries per fan-out transaction</strong>
     * — acceptable for an operator listing, and one sixteenth of the lock budget, which leaves room for
     * everything else the same transaction touches. It is also comfortably inside the OTHER wall the
     * same schema count runs into: 239 relations and ~3.2 MB of empty files per silo means 200 silos is
     * ~48,000 relations and ~650 MB of empty files, which §5.10 calls acceptable and calls unacceptable
     * at 5,000.
     *
     * <p><strong>Raising it is a measurement, not a decision.</strong> §8 Q1 states the experiment
     * exactly: replay the UNION benchmark at N = 50 / 100 / 200 against real {@code tenant_pool} + silo
     * data, not synthetic schemas. If the fan-out queries have by then been replaced by
     * {@code platform.ticket_index} (§8 Q2 — the trigger is 50 silos), the planning half of this
     * derivation stops applying and the ceiling should be re-derived from the lock half alone.
     */
    static final int DEFAULT_MAX_SILOS = 200;

    /** Generous, because it bounds a failure and not a success. See the record's {@code freeze} note. */
    private static final Duration DEFAULT_FREEZE = Duration.ofMinutes(30);

    /**
     * Two seconds. Long enough that an ordinary request which started before the freeze has finished
     * (the SLO's p99 is far under it), short enough to be irrelevant beside the copy. It is a settle
     * delay, not a drain: nothing here tracks in-flight requests, and pretending otherwise would be
     * exactly the kind of comfort that makes a verification step feel unnecessary.
     */
    private static final Duration DEFAULT_DRAIN = Duration.ofSeconds(2);

    TenantPromotionProperties {
        if (maxSilos == null || maxSilos < 1) {
            maxSilos = DEFAULT_MAX_SILOS;
        }
        if (freeze == null || freeze.isZero() || freeze.isNegative()) {
            freeze = DEFAULT_FREEZE;
        }
        if (drain == null || drain.isNegative()) {
            drain = DEFAULT_DRAIN;
        }
    }
}
