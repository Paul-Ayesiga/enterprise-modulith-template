package ug.co.smsone.webhooks.internal;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import ug.co.smsone.organization.MemberRemoved;
import ug.co.smsone.organization.MembershipCreated;
import ug.co.smsone.organization.MembershipRoleChanged;
import ug.co.smsone.organization.OrganizationStatusChanged;
import ug.co.smsone.organization.RolePermissionsChanged;

/**
 * Turns organization domain events into webhook fan-outs. Runs after the change commits (async), so a
 * subscriber outage never affects the originating request. Only org-scoped events are delivered —
 * subscriptions are per-tenant.
 */
@Component
class WebhookEventListener {

    private final WebhookDispatcher dispatcher;

    WebhookEventListener(WebhookDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @ApplicationModuleListener
    void on(MembershipCreated event) {
        String code = WebhookEventType.MEMBER_ADDED.code();
        dispatcher.dispatch(
                code + ":" + event.orgId() + ":" + event.personId() + ":" + event.occurredAt(),
                event.orgId(), code,
                WebhookPayload.of(code, event.orgId(), event.occurredAt())
                        .with("personId", event.personId().toString())
                        .with("role", event.roleCode()));
    }

    @ApplicationModuleListener
    void on(MemberRemoved event) {
        String code = WebhookEventType.MEMBER_REMOVED.code();
        dispatcher.dispatch(
                code + ":" + event.orgId() + ":" + event.personId() + ":" + event.occurredAt(),
                event.orgId(), code,
                WebhookPayload.of(code, event.orgId(), event.occurredAt())
                        .with("personId", event.personId().toString()));
    }

    @ApplicationModuleListener
    void on(MembershipRoleChanged event) {
        String code = WebhookEventType.MEMBER_ROLE_CHANGED.code();
        dispatcher.dispatch(
                code + ":" + event.orgId() + ":" + event.personId() + ":" + event.occurredAt(),
                event.orgId(), code,
                WebhookPayload.of(code, event.orgId(), event.occurredAt())
                        .with("personId", event.personId().toString()));
    }

    @ApplicationModuleListener
    void on(RolePermissionsChanged event) {
        String code = WebhookEventType.ROLE_PERMISSIONS_CHANGED.code();
        dispatcher.dispatch(
                code + ":" + event.orgId() + ":" + event.roleId() + ":" + event.occurredAt(),
                event.orgId(), code,
                WebhookPayload.of(code, event.orgId(), event.occurredAt())
                        .with("roleId", event.roleId().toString()));
    }

    @ApplicationModuleListener
    void on(OrganizationStatusChanged event) {
        String code = WebhookEventType.ORG_STATUS_CHANGED.code();
        dispatcher.dispatch(
                code + ":" + event.orgId() + ":" + event.status() + ":" + event.occurredAt(),
                event.orgId(), code,
                WebhookPayload.of(code, event.orgId(), event.occurredAt())
                        .with("status", event.status()));
    }

    @ApplicationModuleListener
    void on(ug.co.smsone.exchange.JobCompleted event) {
        String code = WebhookEventType.EXCHANGE_JOB_COMPLETED.code();
        dispatcher.dispatch(
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

    @ApplicationModuleListener
    void on(ug.co.smsone.support.TicketEscalated event) {
        String code = WebhookEventType.TICKET_ESCALATED.code();
        dispatcher.dispatch(
                code + ":" + event.ticketId() + ":" + event.priority() + "@" + event.occurredAt(),
                event.orgId(), code,
                WebhookPayload.of(code, event.orgId(), event.occurredAt())
                        .with("ticketId", event.ticketId().toString())
                        .with("priority", event.priority()));
    }

    @ApplicationModuleListener
    void on(ug.co.smsone.subscription.SubscriptionChanged event) {
        String code = WebhookEventType.ORG_SUBSCRIPTION_CHANGED.code();
        dispatcher.dispatch(
                code + ":" + event.orgId() + ":" + event.planCode() + ":" + event.occurredAt(),
                event.orgId(), code,
                WebhookPayload.of(code, event.orgId(), event.occurredAt())
                        .with("plan", event.planCode())
                        .with("status", event.status()));
    }

    @ApplicationModuleListener
    void on(ug.co.smsone.organization.OrganizationDeleted event) {
        // The tenant's LAST outbound event: subscriptions survive as soft rows, but with the org
        // gone nothing later matches them — receivers hear the door close.
        String code = WebhookEventType.ORG_DELETED.code();
        dispatcher.dispatch(
                code + ":" + event.orgId() + ":" + event.occurredAt(),
                event.orgId(), code,
                WebhookPayload.of(code, event.orgId(), event.occurredAt()));
    }
}
