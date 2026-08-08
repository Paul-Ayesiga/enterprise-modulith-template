package ug.co.smsone.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import ug.co.smsone.shared.tenancy.TenantContext;

/**
 * Enables @Async — required by @ApplicationModuleListener (Modulith event consumers run async in
 * their own transaction). Executor comes from Boot's task auto-configuration (virtual threads).
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
public class AsyncConfig {

    /**
     * The tenant axis for <em>every</em> task Boot's shared executors run: declares PLATFORM before the
     * task starts and restores the previous state afterwards (ADR 0010 §3.4).
     *
     * <p><strong>One bean, two executors, and that is deliberate.</strong> Boot's task
     * auto-configuration takes a single {@code TaskDecorator} bean into <em>both</em> the application
     * task executor ({@code @Async}, and therefore every {@code @ApplicationModuleListener}) and the
     * auto-configured {@code TaskScheduler} ({@code @Scheduled}) — {@code TaskExecutorConfigurations}
     * and {@code TaskSchedulingConfigurations} each resolve it through an {@code ObjectProvider}. So
     * this is the outermost thing that runs on a background thread, ahead of every advisor: ahead of
     * ShedLock's lock acquisition, ahead of the transaction advice, ahead of the method body. A second
     * {@code TaskDecorator} bean would not silence this one — Boot composes them — but it would run in
     * an undefined order relative to it, so keep this the only one.
     *
     * <p><strong>Why the axis cannot be declared in the listener bodies instead.</strong>
     * {@code @ApplicationModuleListener} is composed of {@code @Async} + {@code @TransactionalEventListener}
     * + {@code @Transactional(REQUIRES_NEW)}. By the time a listener's first statement runs, the
     * transaction that chose the connection is already open, and {@link TenantContext#setPlatform()}
     * throws there on purpose — pinning after the borrow is the silent-corruption case the whole design
     * exists to prevent. The axis has to be declared on the async hop, which is exactly here. Jobs, workers and
     * runners still declare PLATFORM at their own entry points as well: they are invoked directly by
     * tests and by their own poll loops, where no executor is involved and this decorator never runs.
     *
     * <p><strong>It declares an axis; it never inherits one.</strong> There is no
     * {@code ThreadLocalAccessor} and no capture of the submitting thread's tenant, per ADR 0010: the
     * outbox resubmitter has no thread context at all, so inheriting would make the immediate delivery
     * of an event and its retried delivery behave differently — the one asymmetry an at-least-once
     * outbox must not have. Both go through this decorator and both land on PLATFORM.
     *
     * <p><strong>Phase 2 is where this stops being enough</strong>, and the information needed is
     * already on the wire: 10 of the 15 domain events carry {@code orgId} as their first component and
     * the other 5 map to tables with no {@code org_id}. A listener that writes tenant tables will then
     * take its axis from the event rather than from here — {@code TenantContext.runAs(event.orgId(), …)}
     * around its own work — which needs the listener's transaction to move inside that call. This
     * decorator stays as the floor for the ones that are genuinely platform work (the cache evictors,
     * the search projection).
     *
     * <p>Restores rather than clears, so a pooled platform thread — should the virtual-thread executors
     * ever be swapped for one — carries nothing forward from the task before it.
     */
    @Bean
    TaskDecorator backgroundWorkRunsOnThePlatformAxis() {
        return task -> () -> TenantContext.runAsPlatform(task);
    }
}
