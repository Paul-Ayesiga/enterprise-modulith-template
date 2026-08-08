package ug.co.smsone.testsupport;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * <strong>This class runs with no tenant axis on the test thread.</strong> {@link TenantAxisExtension}
 * skips its {@code setPlatform()} for any class carrying this annotation, so the first connection the
 * class borrows lands in the deliberately empty {@code no_tenant} schema and every unqualified table
 * fails with {@code relation "…" does not exist} (ADR 0010 §3.3).
 *
 * <p><strong>Why an opt-out has to exist at all.</strong> The blanket pin in
 * {@link AbstractIntegrationTest} is what lets 89 database-touching test classes keep working through
 * Phase 1, and for almost all of them it is honest: their subject is a controller or a service that
 * runs inside a request, where {@code CurrentUserFilter} has already pinned an axis. But for a handful
 * of classes the subject <em>is</em> the pinning — the router, the fail-closed layers, a job that must
 * declare its own axis rather than inherit one. Pin the thread under those and they pass with the
 * production pin deleted, which is the suite going green while the application is broken in exactly the
 * way this mechanism exists to prevent. {@code MappedSchemaValidatorIntegrationTest} is the sharpest
 * example: its subject is the {@code TenantContext.runAsPlatform} wrapper inside
 * {@code MappedSchemaValidator}, and with a harness pin above it that wrapper can be deleted with the
 * test still green.
 *
 * <p><strong>An opted-out class can still seed.</strong> Absence applies to the whole test method, so
 * fixture writes need an axis of their own — {@code TenantContext.callAsPlatform(() -> EdgeSeed.person(
 * jdbc, subject))}. The scoped helper, not a bare {@code setPlatform()}: it restores absence in a
 * {@code finally}, so the window the test is actually about stays unpinned even when seeding throws.
 * If instead only ONE call needs to run unpinned, do not annotate the class — wrap that call in
 * {@link TenantAxis#withNoAxis} and leave the rest of the class on the harness pin.
 *
 * <p>The {@code value} is mandatory and is the point of the annotation: an opt-out that does not say
 * why reads as a workaround, and the next person to see a red test will reach for it. Say what the
 * class proves that a pinned harness would make unfalsifiable.
 *
 * <p>Deliberately <strong>not</strong> {@code @Inherited} and looked up with
 * {@code getDeclaredAnnotation}: running without an axis is a claim each class makes at its own
 * declaration. A subclass that quietly inherited it would be the accident this annotation exists to
 * rule out. For the same reason {@code TenantAxisHarnessTest.everyOptOutIsOnTheRoster} asserts the set
 * of annotated classes matches a written list — adding a name there is the deliberate part.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface NoTenantAxis {

    /** Why this class must run unpinned — what a harness pin above it would make unfalsifiable. */
    String value();
}
