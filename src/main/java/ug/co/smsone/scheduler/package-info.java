/**
 * Scheduler module: it owns the ShedLock JDBC infrastructure that makes a {@code @Scheduled} method
 * fire exactly once across all application instances, and it hosts the cross-cutting purges
 * ({@code EventPublicationPurgeJob}, {@code IdempotencyPurgeJob}, {@code SoftDeletePurgeJob}).
 *
 * <p>Cron work belongs here by default. A job that needs another module's {@code internal}
 * collaborators is the exception and stays in that module — {@code identity.internal
 * .IdentityReconciliationJob} needs the Keycloak gateway and the user repository, and moving it here
 * would mean either breaking a module boundary or inventing a port for one caller. What such a job may
 * never do is skip {@code @SchedulerLock}: this module owns the infrastructure, not a monopoly on
 * {@code @Scheduled}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Scheduler")
package ug.co.smsone.scheduler;
