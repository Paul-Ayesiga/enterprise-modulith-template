package ug.co.smsone.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;
import ug.co.smsone.shared.tenancy.JobAxis;
import ug.co.smsone.shared.tenancy.TenantContext;

/**
 * <b>The Phase 3 gate: every scheduled job declares the tenancy axis it runs on, and the declaration is
 * checked against the code (ADR 0010 §3.4, §7 Phase 3).</b>
 *
 * <p><b>The jobs are ENUMERATED, never listed.</b> Every assertion below starts from the compiled
 * classes and finds {@code @SchedulerLock} itself, so the fifteenth job is covered the moment it is
 * written, by whoever writes it, in whichever module they put it in. That is not a hypothetical: ADR
 * 0010 §3.4 says "the 14 {@code @SchedulerLock} jobs" and there are fifteen — V55's
 * {@code OrgMembershipIndexReconciler} arrived after the ADR was drafted, and a hand-kept list in this
 * file would have quietly stopped covering the fleet before the ink dried. The count is deliberately
 * NOT asserted for the same reason: a test that has to be edited to add a job is a test that will be
 * edited to add a job, and the edit that satisfies it is the one that removes the coverage.
 *
 * <p>ArchUnit rather than the Spring context, and that difference is load-bearing. Two jobs are behind
 * {@code @ConditionalOnProperty} ({@code UsageExportJob} ships disabled, {@code IdentityReconciliationJob}
 * can be turned off), so an enumeration over beans would silently skip exactly the jobs a fork is most
 * likely to get wrong. Compiled classes have no opinion about the active profile.
 */
class ScheduledJobAxisTest {

    /**
     * Production classes only. A test fixture that mimics a job — and several do drive one directly —
     * has no axis to declare and no lease to derive.
     */
    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("ug.co.smsone");

    private static final String TENANT_CONTEXT = TenantContext.class.getName();

    /** Every {@code @SchedulerLock} method in the application, found by the annotation. */
    private static List<JavaMethod> scheduledJobs() {
        List<JavaMethod> jobs = new ArrayList<>();
        for (JavaClass type : PRODUCTION) {
            for (JavaMethod method : type.getMethods()) {
                if (method.isAnnotatedWith(SchedulerLock.class)) {
                    jobs.add(method);
                }
            }
        }
        // Non-empty is its own assertion: if the importer ever stops seeing these classes — a package
        // rename, a build-layout change — every other test in this file would pass over an empty list
        // and the gate would be gone without a single failure.
        assertThat(jobs)
                .as("no @SchedulerLock methods were found at all — the gate is not actually looking at "
                        + "the production classes")
                .isNotEmpty();
        return jobs;
    }

    /**
     * <b>THE GATE.</b> Every job says which axis it runs on.
     *
     * <p>What makes this worth having rather than decorative: before Phase 2 the axis was unobservable,
     * because every {@code Tenant} state resolved to the same schema and a wrong pin cost nothing. After
     * Phase 2 a tenant-tier table read on the platform axis raises {@code relation "…" does not exist}
     * inside a job whose exception lands in a 04:00 log, and the promise that job carries just stops
     * being kept. Three jobs were in that state and nothing in the build said so.
     */
    @Test
    void everyScheduledJobDeclaresATenancyAxis() {
        List<String> undeclared = new ArrayList<>();
        for (JavaMethod job : scheduledJobs()) {
            job.tryGetAnnotationOfType(JobAxis.class)
                    .filter(axis -> axis.value().length > 0)
                    .ifPresentOrElse(axis -> { }, () -> undeclared.add(job.getFullName()));
        }
        assertThat(undeclared)
                .as("every @SchedulerLock method must carry @JobAxis naming the axis it pins. A job with "
                        + "no axis runs on whatever the scheduler thread was left holding, which off a "
                        + "request thread is Tenant.ABSENT — the empty no_tenant schema — so its first "
                        + "unqualified table fails and its retention, escalation or dunning promise stops "
                        + "being kept silently (ADR 0010 §3.3, §3.4)")
                .isEmpty();
    }

    /**
     * The declaration is checked against the code, so it cannot drift into a comment that used to be
     * true. A job declaring {@code PLATFORM} must actually call {@code runAsPlatform}/{@code
     * callAsPlatform} somewhere in its own class; one declaring {@code TENANT} must call
     * {@code runAs}/{@code callAs}; one declaring both must do both.
     *
     * <p><b>Class-scoped rather than method-scoped, deliberately.</b> Most of these jobs split the
     * scheduled entry from the work so a test can drive the logic without ShedLock's silent same-name
     * relock skip, and the pin belongs on the shared method — {@code UsageExportJob.export()},
     * {@code IdentityReconciliationJob.run()}, {@code ExchangeScheduleFiringJob.fireDueSchedules()} —
     * precisely so the direct call and the cron call declare the same axis. A check that insisted the
     * pin be in the annotated method would push it back into the entry point and break that property.
     *
     * <p>What this cannot catch, stated so nobody reads more into a green build than is there: it proves
     * the pin EXISTS in the class, not that it wraps the right statements. A job that opens a tenant
     * span around half its work and leaves the other half on the platform axis passes here. The
     * behavioural tests own that; this owns the failure mode where a job simply never declares one, and
     * the one where a declaration and a body disagree outright.
     */
    @Test
    void aDeclaredAxisIsBackedByAnActualPinInTheSameClass() {
        Map<String, String> mismatches = new LinkedHashMap<>();
        for (JavaMethod job : scheduledJobs()) {
            JobAxis declared = job.tryGetAnnotationOfType(JobAxis.class).orElse(null);
            if (declared == null) {
                continue; // already failed, loudly, in the gate above
            }
            Set<String> pins = tenantContextCallsIn(job.getOwner());
            for (JobAxis.Axis axis : declared.value()) {
                if (pins.stream().noneMatch(axis.pinningMethods()::contains)) {
                    mismatches.put(job.getFullName(), "declares " + axis + " but calls none of "
                            + new TreeSet<>(axis.pinningMethods()) + " — TenantContext calls found: "
                            + new TreeSet<>(pins));
                }
            }
        }
        assertThat(mismatches)
                .as("a @JobAxis that names an axis the job never pins is worse than no annotation: it is "
                        + "a claim the build appears to have checked. Phase 1 left several jobs pinned "
                        + "PLATFORM while every axis still resolved to one schema, and Phase 2 turned "
                        + "each of those into a nightly failure")
                .isEmpty();
    }

    /**
     * The other direction: a job that pins an axis it did not declare. This is the drift that actually
     * happened — {@code SlaEscalationJob}, {@code DunningJob} and {@code TrialExpiryJob} each grew a
     * second span (or swapped the one they had) while the prose above them still said PLATFORM — and it
     * is the half a presence check would miss entirely, because the annotation would be there and
     * satisfied.
     */
    @Test
    void aJobThatPinsAnAxisAlsoDeclaresIt() {
        Map<String, String> undeclared = new LinkedHashMap<>();
        for (JavaMethod job : scheduledJobs()) {
            JobAxis declared = job.tryGetAnnotationOfType(JobAxis.class).orElse(null);
            if (declared == null) {
                continue;
            }
            Set<JobAxis.Axis> claimed = EnumSet.noneOf(JobAxis.Axis.class);
            claimed.addAll(Arrays.asList(declared.value()));
            Set<String> pins = tenantContextCallsIn(job.getOwner());
            for (JobAxis.Axis axis : JobAxis.Axis.values()) {
                boolean pinned = pins.stream().anyMatch(axis.pinningMethods()::contains);
                if (pinned && !claimed.contains(axis)) {
                    undeclared.put(job.getFullName(),
                            "pins " + axis + " (" + new TreeSet<>(pins) + ") but declares only " + claimed);
                }
            }
        }
        assertThat(undeclared)
                .as("a job that opens a span on an axis must say so. An undeclared second span is how a "
                        + "one-line change turns a single-tier job into a cross-tier one without anybody "
                        + "re-deriving its lease for the fan-out that comes with it")
                .isEmpty();
    }

    /**
     * Every job states its own lease rather than inheriting {@code EnableSchedulerLock}'s
     * {@code defaultLockAtMostFor}.
     *
     * <p>This is not tidiness. {@code lockAtMostFor} is the answer to "how long may this run before a
     * second replica is allowed to start it concurrently", and a default is by construction a number
     * nobody sized against the job holding it. Getting it too short is a correctness bug — two
     * instances purging the same tables, escalating the same tickets, posting the same usage batch —
     * and the symptom is intermittent and lands at 04:00. Forcing the declaration is what makes the
     * derivation happen; the paragraph next to each value is where it lives.
     */
    @Test
    void everyScheduledJobStatesItsOwnLease() {
        List<String> defaulted = new ArrayList<>();
        for (JavaMethod job : scheduledJobs()) {
            SchedulerLock lock = job.getAnnotationOfType(SchedulerLock.class);
            if (lock.lockAtMostFor().isBlank()) {
                defaulted.add(job.getFullName());
            }
        }
        assertThat(defaulted)
                .as("every @SchedulerLock must set lockAtMostFor explicitly and derive it in prose next "
                        + "to the value — a lease that expires under a running pass lets a second replica "
                        + "start a concurrent one, which is a correctness bug and not a slow job")
                .isEmpty();
    }

    /**
     * Two jobs sharing a ShedLock name share a LEASE, and ShedLock skips a same-name relock in silence —
     * so the second job simply never runs, on any instance, with no error anywhere. Nothing else in the
     * build would notice, and the names are hand-written strings in fifteen different files.
     */
    @Test
    void everyScheduledJobHoldsItsOwnLock() {
        Map<String, List<String>> byName = new LinkedHashMap<>();
        for (JavaMethod job : scheduledJobs()) {
            String name = job.getAnnotationOfType(SchedulerLock.class).name();
            byName.computeIfAbsent(name, key -> new ArrayList<>()).add(job.getFullName());
        }
        Map<String, List<String>> shared = new LinkedHashMap<>(byName);
        shared.values().removeIf(holders -> holders.size() < 2);
        assertThat(shared)
                .as("two @SchedulerLock methods with the same name share one lease, and ShedLock skips a "
                        + "same-name relock silently — the second job stops running everywhere, forever, "
                        + "and logs nothing")
                .isEmpty();
    }

    /** Names of {@link TenantContext} methods this class calls, anywhere in its own body. */
    private static Set<String> tenantContextCallsIn(JavaClass type) {
        Set<String> called = new LinkedHashSet<>();
        for (JavaMethodCall call : type.getMethodCallsFromSelf()) {
            if (TENANT_CONTEXT.equals(call.getTargetOwner().getFullName())) {
                called.add(call.getTarget().getName());
            }
        }
        return called;
    }
}
