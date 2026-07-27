/**
 * Scheduler module: cron jobs guarded by ShedLock's JDBC provider so each job fires exactly once
 * across all application instances. Add jobs here; never call {@code @Scheduled} methods directly
 * from business code.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Scheduler")
package ug.co.smsone.scheduler;
