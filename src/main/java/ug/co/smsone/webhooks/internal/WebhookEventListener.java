package ug.co.smsone.webhooks.internal;

import java.util.UUID;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import ug.co.smsone.organization.MemberRemoved;
import ug.co.smsone.organization.MembershipCreated;
import ug.co.smsone.organization.MembershipRoleChanged;
import ug.co.smsone.organization.OrganizationStatusChanged;
import ug.co.smsone.organization.RolePermissionsChanged;
import ug.co.smsone.shared.tenancy.TenantContext;

/**
 * Turns organization domain events into webhook fan-outs. Runs after the change commits (async), so a
 * subscriber outage never affects the originating request. Only org-scoped events are delivered —
 * subscriptions are per-tenant.
 *
 * <h2>Why every method is spelled {@code @Async} + {@code @TransactionalEventListener} (ADR 0010 §3.2)</h2>
 *
 * <p>{@link ApplicationModuleListener} is the same three annotations with {@code @Transactional}
 * bundled in, and that bundling is what this module cannot have. {@code webhook_subscription} and
 * {@code webhook_delivery} are TENANT-tier, so the fan-out has to run on the axis of the org the event
 * is about — and the axis has to be declared BEFORE a connection is borrowed, because the
 * {@code search_path} is chosen at borrow. Enter the method with the transaction already open and
 * {@code TenantContext} refuses to pin (it throws, on purpose: the pin would be a silent no-op and
 * every statement would keep running on whatever the pooled listener thread last had). So the
 * annotation is unbundled and {@link #fanOut} puts the pieces back in the only order that works: pin,
 * then transact. Same split, same reason, as {@code DeviceTrustRevocationListener} and
 * {@code OrganizationBillingListener}.
 *
 * <p>Nothing else changes. The publication registry still records and still retries an incomplete
 * listener — it tracks {@code @TransactionalEventListener}s, which these still are — and the
 * transaction is still this listener's own, still opened after the publisher committed.
 *
 * <p>An axis is NOT inherited from the publishing thread, and must not be: {@code OutboxResubmissionJob}
 * re-publishes with no thread context at all, so a listener that leaned on inheritance would behave
 * differently on the retry than on the first delivery — the failure mode ADR 0010 §3.2 refuses a
 * {@code ThreadLocalAccessor} to prevent. The org comes off the event, which every one of these
 * carries.
 */
@Component
class WebhookEventListener {

    private final WebhookDispatcher dispatcher;
    private final TransactionTemplate transactions;

    WebhookEventListener(WebhookDispatcher dispatcher, TransactionTemplate transactions) {
        this.dispatcher = dispatcher;
        this.transactions = transactions;
    }

    @Async
    @TransactionalEventListener
    void on(MembershipCreated event) {
        String code = WebhookEventType.MEMBER_ADDED.code();
        fanOut(
                code + ":" + event.orgId() + ":" + event.personId() + ":" + event.occurredAt(),
                event.orgId(), code,
                WebhookPayload.of(code, event.orgId(), event.occurredAt())
                        .with("personId", event.personId().toString())
                        .with("role", event.roleCode()));
    }

    @Async
    @TransactionalEventListener
    void on(MemberRemoved event) {
        String code = WebhookEventType.MEMBER_REMOVED.code();
        fanOut(
                code + ":" + event.orgId() + ":" + event.personId() + ":" + event.occurredAt(),
                event.orgId(), code,
                WebhookPayload.of(code, event.orgId(), event.occurredAt())
                        .with("personId", event.personId().toString()));
    }

    @Async
    @TransactionalEventListener
    void on(MembershipRoleChanged event) {
        String code = WebhookEventType.MEMBER_ROLE_CHANGED.code();
        fanOut(
                code + ":" + event.orgId() + ":" + event.personId() + ":" + event.occurredAt(),
                event.orgId(), code,
                WebhookPayload.of(code, event.orgId(), event.occurredAt())
                        .with("personId", event.personId().toString()));
    }

    @Async
    @TransactionalEventListener
    void on(RolePermissionsChanged event) {
        String code = WebhookEventType.ROLE_PERMISSIONS_CHANGED.code();
        fanOut(
                code + ":" + event.orgId() + ":" + event.roleId() + ":" + event.occurredAt(),
                event.orgId(), code,
                WebhookPayload.of(code, event.orgId(), event.occurredAt())
                        .with("roleId", event.roleId().toString()));
    }

    @Async
    @TransactionalEventListener
    void on(OrganizationStatusChanged event) {
        String code = WebhookEventType.ORG_STATUS_CHANGED.code();
        fanOut(
                code + ":" + event.orgId() + ":" + event.status() + ":" + event.occurredAt(),
                event.orgId(), code,
                WebhookPayload.of(code, event.orgId(), event.occurredAt())
                        .with("status", event.status()));
    }

    @Async
    @TransactionalEventListener
    void on(ug.co.smsone.exchange.JobCompleted event) {
        String code = WebhookEventType.EXCHANGE_JOB_COMPLETED.code();
        fanOut(
                code + ":" + event.jobId() + ":" + event.outcome() + "@" + event.occurredAt(),
                event.orgId(), code,
                WebhookPayload.of(code, event.orgId(), event.occurredAt())
                        .with("jobId", event.jobId().toString())
                        .with("handler", event.handler())
                        .with("jobType", event.jobType())
                        .with("outcome", event.outcome())
                        .with("processed", String.valueOf(event.processed()))
                        .with("failed", String.valueOf(event.failed())));
    }

    @Async
    @TransactionalEventListener
    void on(ug.co.smsone.support.TicketEscalated event) {
        String code = WebhookEventType.TICKET_ESCALATED.code();
        fanOut(
                code + ":" + event.ticketId() + ":" + event.priority() + "@" + event.occurredAt(),
                event.orgId(), code,
                WebhookPayload.of(code, event.orgId(), event.occurredAt())
                        .with("ticketId", event.ticketId().toString())
                        .with("priority", event.priority()));
    }

    @Async
    @TransactionalEventListener
    void on(ug.co.smsone.subscription.SubscriptionChanged event) {
        String code = WebhookEventType.ORG_SUBSCRIPTION_CHANGED.code();
        fanOut(
                code + ":" + event.orgId() + ":" + event.planCode() + ":" + event.occurredAt(),
                event.orgId(), code,
                WebhookPayload.of(code, event.orgId(), event.occurredAt())
                        .with("plan", event.planCode())
                        .with("status", event.status()));
    }

    @Async
    @TransactionalEventListener
    void on(ug.co.smsone.organization.OrganizationDeleted event) {
        // The tenant's LAST outbound event: subscriptions survive as soft rows, but with the org
        // gone nothing later matches them — receivers hear the door close.
        String code = WebhookEventType.ORG_DELETED.code();
        fanOut(
                code + ":" + event.orgId() + ":" + event.occurredAt(),
                event.orgId(), code,
                WebhookPayload.of(code, event.orgId(), event.occurredAt()));
    }

    /**
     * Pin, then transact — the ordering the class note argues for, in the one place all nine listeners
     * go through so no tenth can be added without it.
     *
     * <p>The axis is the EVENT'S org and not the pooled-tenant constant the jobs use, because here one
     * is actually known: a fan-out is a tenant's own outbound traffic, read from that tenant's
     * subscriptions and queued into that tenant's delivery log. Phase 5 needs no edit here at all —
     * {@code runAs} already routes a promoted org to its silo — which is the difference between work
     * that names its tenant and a sweep that has to iterate them.
     *
     * <p>A {@code TransactionTemplate} rather than a {@code @Transactional} method on this class, for
     * the reason {@code DeviceTrustRevocationListener} spells out: annotating a private method — or
     * calling an annotated one on {@code this} — is a self-invocation the proxy never sees, so no
     * transaction would start and the {@code EventInbox} row would commit independently of the
     * deliveries it is meant to make idempotent. That is precisely the split that turns an at-least-once
     * redelivery into a silently dropped fan-out: the inbox says "done", the queue holds nothing.
     *
     * <p><strong>The {@code catch} is ADR 0011's, and it is not defensive coding.</strong> On a tenant
     * served from another database the {@code EventInbox} claim cannot commit with the deliveries — two
     * databases, no atomic write — so a fan-out that throws would leave the message marked processed and
     * nothing queued, and the at-least-once redelivery this listener depends on would be de-duplicated
     * into silence. Handing the claim back restores that redelivery. It is deliberately unconditional:
     * for a co-located tenant the claim rolled back with the work and the delete matches nothing, and a
     * failure path that had to first ask which topology it was in is a failure path that will ask wrong.
     */
    private void fanOut(String messageId, UUID orgId, String code, WebhookPayload payload) {
        try {
            TenantContext.runAs(orgId, () ->
                    transactions.executeWithoutResult(tx ->
                            dispatcher.dispatch(messageId, orgId, code, payload)));
        } catch (RuntimeException failure) {
            dispatcher.unclaim(messageId);
            throw failure;
        }
    }
}
