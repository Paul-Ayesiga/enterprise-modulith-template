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
                code + ":" + event.orgId() + ":" + event.subject() + ":" + event.occurredAt(),
                event.orgId(), code,
                WebhookPayload.of(code, event.orgId(), event.occurredAt())
                        .with("subject", event.subject())
                        .with("role", event.roleCode()));
    }

    @ApplicationModuleListener
    void on(MemberRemoved event) {
        String code = WebhookEventType.MEMBER_REMOVED.code();
        dispatcher.dispatch(
                code + ":" + event.orgId() + ":" + event.subject() + ":" + event.occurredAt(),
                event.orgId(), code,
                WebhookPayload.of(code, event.orgId(), event.occurredAt())
                        .with("subject", event.subject()));
    }

    @ApplicationModuleListener
    void on(MembershipRoleChanged event) {
        String code = WebhookEventType.MEMBER_ROLE_CHANGED.code();
        dispatcher.dispatch(
                code + ":" + event.orgId() + ":" + event.subject() + ":" + event.occurredAt(),
                event.orgId(), code,
                WebhookPayload.of(code, event.orgId(), event.occurredAt())
                        .with("subject", event.subject()));
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
}
