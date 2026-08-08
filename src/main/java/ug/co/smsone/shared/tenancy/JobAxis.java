package ug.co.smsone.shared.tenancy;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
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
        PLATFORM("runAsPlatform", "callAsPlatform"),

        /**
         * One tenant's own tables, reached bare so {@code search_path} places them. Today one span over
         * {@code tenant_pool} covers the whole fleet; ADR 0010 Phase 5 turns that span into a loop over
         * {@code platform.tenant_placement}, which is a change to the job's body and not to this
         * declaration.
         */
        TENANT("runAs", "callAs");

        private final Set<String> pins;

        Axis(String... pins) {
            this.pins = Set.copyOf(List.of(pins));
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
    }
}
