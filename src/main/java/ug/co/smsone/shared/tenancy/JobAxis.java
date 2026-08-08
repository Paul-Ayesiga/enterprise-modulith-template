package ug.co.smsone.shared.tenancy;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Set;

/**
 * The tenancy axis every {@code @SchedulerLock} job runs on, written down as an annotation rather than
 * left to be inferred from the body (ADR 0010 §3.4).
 *
 * <p><strong>Why a declaration and not a comment.</strong> Phase 1 pinned nine of these jobs
 * {@code PLATFORM} when every axis still resolved to the same schema, so the pin was a no-op and being
 * wrong cost nothing. Phase 2 made it cost everything: a tenant-tier table read on the platform axis
 * does not return zero rows, it raises {@code relation "ticket" does not exist} inside a job whose
 * stack trace lands in a 04:00 log nobody is watching, and the promise that job carries — SLA
 * escalation, dunning, retention — stops being kept silently. Three jobs were in exactly that state
 * ({@code SlaEscalationJob}, {@code DunningJob}, {@code TrialExpiryJob}) and nothing in the build said
 * so. This annotation is what makes the axis a fact the build can check.
 *
 * <p><strong>It is checked against the code, not just for presence.</strong>
 * {@code ScheduledJobAxisTest} enumerates every method carrying {@code @SchedulerLock} straight off the
 * compiled classes — never a hand-kept list, so the fifteenth job is covered the day it is written —
 * asserts each one declares an axis here, and then asserts the declaring class actually calls the
 * {@link TenantContext} entry points that axis names. A job that declares {@code PLATFORM} and pins a
 * tenant fails the build; so does one that declares one axis and opens two spans.
 *
 * <p><strong>What it deliberately does not say.</strong> Not the cursor, and not the lease. Both are
 * per-job judgements with a paragraph of reasoning behind them and no honest enum — a lease is sized
 * against a measured pass or it is a guess, and an annotation cannot tell those apart. Each job says
 * which it is, in prose, next to the number.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface JobAxis {

    /**
     * One axis, or both when the job opens two spans because its tables live on both sides of the tier
     * boundary. Never empty — an axis-less job is the failure this whole annotation exists to catch.
     */
    Axis[] value();

    /**
     * The axes a job can declare, each carrying the {@link TenantContext} methods that DECLARE it.
     *
     * <p>The method names live here rather than in the test so the mapping has one home: they are what
     * binds the annotation to the code, and a binding that drifts is worse than no binding, because it
     * makes the build's silence mean nothing.
     */
    enum Axis {

        /**
         * The tables the whole installation shares — {@code platform.<table>}, one copy, never
         * per-tenant. A job is PLATFORM when everything it touches is on that tier, not when it happens
         * to be triggered by no request.
         */
        PLATFORM(Set.of("runAsPlatform", "callAsPlatform"), Set.of()),

        /**
         * One tenant's own tables, reached bare so {@code search_path} places them. Phase 5 turned the
         * single span over {@code tenant_pool} into a loop over {@code tenant_pool} plus every ACTIVE
         * silo — a change to the job's body, and to how many times the pin is taken, but not to this
         * declaration.
         *
         * <p>Which is why it has a DELEGATED pinner. Eight jobs now take that pin through
         * {@link TenantHomeSweep#over}, because the loop it wraps carries four other properties (a
         * per-home budget, a resumable rotation, per-home failure isolation, the pool's own floor) that
         * no job should be re-deriving. The sweep calls {@code TenantContext.runAs} itself, so the axis
         * is genuinely pinned; what moved is only which class the call appears in.
         */
        TENANT(Set.of("runAs", "callAs"),
                Set.of(TenantHomeSweep.class.getName() + "#over"));

        private final Set<String> pins;
        private final Set<String> delegated;

        Axis(Set<String> pins, Set<String> delegated) {
            this.pins = Set.copyOf(pins);
            this.delegated = Set.copyOf(delegated);
        }

        /**
         * The {@link TenantContext} method names that declare this axis. Matched EXACTLY and never by
         * prefix — {@code runAs} is a prefix of {@code runAsPlatform}, so a prefix match would let every
         * platform job satisfy a TENANT declaration and the check would pass on all fifteen while
         * proving nothing.
         */
        public Set<String> pinningMethods() {
            return pins;
        }

        /**
         * Helpers that take this axis's pin on the caller's behalf, as {@code <fully.qualified.Class>#method}.
         *
         * <p>They are listed HERE, beside the direct pinners, for the reason the class note gives: a
         * binding that lives in the test drifts from the code it binds, and the drift is invisible
         * because the build stays green. A job whose only pin is a delegated one still declares the axis
         * it runs on, and {@code ScheduledJobAxisTest} still checks the declaration against the body —
         * it just knows about one more shape the body can take.
         *
         * <p><strong>Only add a helper here if it really pins.</strong> The whole value of the check is
         * that a declaration cannot become a comment; a class listed here that merely <em>looks</em> like
         * a fan-out would satisfy every job that touched it and prove nothing about any of them.
         */
        public Set<String> delegatedPinners() {
            return delegated;
        }
    }
}
