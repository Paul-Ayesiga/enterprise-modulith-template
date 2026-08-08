package ug.co.smsone.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.shared.tenancy.Tenant;
import ug.co.smsone.shared.tenancy.TenantContext;

/**
 * The harness that gives 89 database-touching test classes an axis, and the assertions that stop it
 * from becoming the reason the suite is green (ADR 0010 §3.4).
 *
 * <p>A blanket pin is a strange thing to put in a base class: it makes every test pass, including the
 * ones that exist to catch a missing pin in production code. So this class holds both halves — that
 * the pin is really there and really works, and that opting out of it is possible, visible, and
 * enumerated.
 */
class TenantAxisHarnessTest extends AbstractIntegrationTest {

    /**
     * Every class in the suite that runs without a tenant axis, and the whole point is that this list
     * is written by hand.
     *
     * <p>{@link NoTenantAxis} is the escape hatch from a mechanism designed to fail closed, which makes
     * it the most attractive way to turn a red test green for the wrong reason — the failure it
     * produces ({@code relation "…" does not exist}) looks like a harness problem, and the annotation
     * looks like the harness fix. Requiring a second, deliberate edit here is what turns "add the
     * annotation" into a decision somebody has to defend in review.
     *
     * <p>If this test fails, do not add the name until you can say what a harness pin would make
     * unfalsifiable in that class. If the answer is "nothing", the class does not want the annotation —
     * it wants {@link TenantAxis#withNoAxis} around the one call whose axis is the subject.
     */
    private static final Set<String> ROSTER = Set.of(
            // Absence is the subject: the router's fail-closed path (ADR 0010 §3.3 layers 2 and 3).
            "ug.co.smsone.shared.persistence.TenantRoutingDataSourceIntegrationTest",
            // The subject is MappedSchemaValidator's own runAsPlatform, at ApplicationReadyEvent.
            "ug.co.smsone.shared.persistence.MappedSchemaValidatorIntegrationTest",
            // The subject is CurrentUserFilter's pin — entry point 1 of ADR 0010 §3.2.
            "ug.co.smsone.shared.security.CurrentUserFilterTenantPinTest",
            // The subject is McpToolDispatcher's pin and restore — entry point 2 of §3.2.
            "ug.co.smsone.mcp.internal.McpTenantPinTest",
            // The harness's own proof that the opt-out opts out.
            "ug.co.smsone.testsupport.TenantAxisOptOutTest");

    /** The shortest reason that can carry the required content: what a pin would make unfalsifiable. */
    private static final int MEANINGFUL_REASON = 40;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void theHarnessPinsThePlatformAxisAndTheDatabaseIsReachableBecauseOfIt() {
        assertThat(TenantContext.current()).isEqualTo(Tenant.PLATFORM);
        // Not decoration: the pin is only worth anything if it survives all the way to the borrow. An
        // unqualified table is the exact thing that resolves to nothing under the absent state.
        assertThat(jdbc.queryForObject("select count(*) from organization", Long.class)).isNotNull();
    }

    @Test
    void everyClassThatRunsWithoutAnAxisIsOnTheRoster() {
        assertThat(optedOutClassNames())
                .as("a class opting out of the tenant axis is a decision, not a fix — see ROSTER's javadoc "
                        + "before adding or removing a name here")
                .containsExactlyInAnyOrderElementsOf(ROSTER);
    }

    @Test
    void everyOptOutSaysWhatAPinWouldMakeUnfalsifiable() {
        for (JavaClass optedOut : optedOutClasses()) {
            String reason = optedOut.getAnnotationOfType(NoTenantAxis.class).value();

            assertThat(reason.strip().length())
                    .as("%s opts out without saying why — a reason that does not name the assertion a "
                            + "harness pin would make vacuous is how this annotation becomes a workaround",
                            optedOut.getSimpleName())
                    .isGreaterThanOrEqualTo(MEANINGFUL_REASON);
        }
    }

    /**
     * The property every leak assertion in the suite is built on. If {@link TenantAxis}' executor handed
     * out a fresh thread per task — or a virtual one — then "the next unit of work inherits nothing"
     * would be true by construction and would prove nothing about any {@code finally} block.
     */
    @Test
    void theSharedPoolIsOneReusedPlatformThread() throws Exception {
        String first = TenantAxis.callOnPooledThread(() -> Thread.currentThread().getName());
        String second = TenantAxis.callOnPooledThread(() -> Thread.currentThread().getName());
        boolean virtual = TenantAxis.callOnPooledThread(() -> Thread.currentThread().isVirtual());

        assertThat(second).as("a fresh thread per task makes every leak assertion vacuous").isEqualTo(first);
        assertThat(virtual)
                .as("spring.threads.virtual.enabled must not reach this pool, or there is no reuse to leak "
                        + "across")
                .isFalse();
    }

    /**
     * The obligation every job, worker and async listener carries, stated as an executable fact rather
     * than as advice: work handed to a pooled platform thread arrives with no axis, and unless it
     * declares one it reads the empty schema.
     *
     * <p>This is what a test for {@code SoftDeletePurgeJob} or {@code NotificationDeliveryWorker} looks
     * like once that code declares its own axis — drive it through {@link TenantAxis#onPooledThread} and
     * the missing {@code runAsPlatform} is a failure instead of an invisible inheritance from the test
     * thread.
     */
    @Test
    void workOnAPooledThreadArrivesWithNoAxisAndTheDeclarationIsWhatFixesIt() throws Exception {
        assertThat(TenantAxis.callOnPooledThread(TenantContext::current))
                .as("the harness pins the TEST thread only — the scheduler's threads are not it")
                .isEqualTo(Tenant.ABSENT);

        // The ROOT cause, because a BadSqlGrammarException's own message is task + SQL and says nothing
        // about why Postgres refused (Spring Framework 6 stopped appending the cause), so a check on the
        // wrapper's message would fail here even though the mechanism worked. The relation is named too,
        // so this stays an assertion about the empty schema rather than about any SQL error.
        TenantAxis.onPooledThread(() -> assertThatThrownBy(
                () -> jdbc.queryForObject("select count(*) from organization", Long.class))
                .isInstanceOf(BadSqlGrammarException.class)
                .rootCause()
                .hasMessageContaining("relation \"organization\" does not exist"));

        TenantAxis.onPooledThread(() -> TenantContext.runAsPlatform(
                () -> assertThat(jdbc.queryForObject("select count(*) from organization", Long.class))
                        .isNotNull()));

        assertThat(TenantAxis.callOnPooledThread(TenantContext::current))
                .as("and runAsPlatform hands the pooled thread back clean, which is the half a "
                        + "request-path test can never observe under virtual threads")
                .isEqualTo(Tenant.ABSENT);
    }

    @Test
    void withNoAxisGivesTheTestThreadItsHarnessPinBackAfterwards() throws Exception {
        TenantAxis.withNoAxis(() -> assertThat(TenantContext.current()).isEqualTo(Tenant.ABSENT));

        assertThat(TenantContext.current())
                .as("a drive call that cleared and never restored would poison every line after it")
                .isEqualTo(Tenant.PLATFORM);
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private static Set<String> optedOutClassNames() {
        return optedOutClasses().stream()
                .map(JavaClass::getName)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * Read out of the compiled classes rather than from a list of imports, so a class that adds the
     * annotation without touching this file is still caught. {@link NoTenantAxis} lives in test sources,
     * so nothing in main can carry it and no filtering by location is needed.
     */
    private static Set<JavaClass> optedOutClasses() {
        JavaClasses compiled = new ClassFileImporter().importPackages("ug.co.smsone");
        return StreamSupport.stream(compiled.spliterator(), false)
                .filter(candidate -> candidate.isAnnotatedWith(NoTenantAxis.class))
                .collect(Collectors.toSet());
    }
}
