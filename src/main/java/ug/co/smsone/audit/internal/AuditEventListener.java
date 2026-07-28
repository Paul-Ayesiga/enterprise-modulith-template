package ug.co.smsone.audit.internal;

import java.time.Clock;
import java.time.Instant;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import ug.co.smsone.identity.UserActivated;
import ug.co.smsone.identity.UserProvisioned;
import ug.co.smsone.organization.MemberRemoved;
import ug.co.smsone.organization.MembershipCreated;
import ug.co.smsone.organization.MembershipRoleChanged;
import ug.co.smsone.organization.OrganizationRegistered;
import ug.co.smsone.organization.OrganizationStatusChanged;
import ug.co.smsone.organization.RolePermissionsChanged;
import ug.co.smsone.settings.FeatureFlagChanged;
import ug.co.smsone.settings.SettingChanged;

/**
 * Records an audit row for each domain event other modules publish. Message ids are derived from the
 * event's business identity + {@code occurredAt} so a genuine later change is audited while an
 * at-least-once redelivery of the same event is not.
 */
@Component
class AuditEventListener {

    private final AuditRecorder recorder;
    private final Clock clock;

    AuditEventListener(AuditRecorder recorder, Clock clock) {
        this.recorder = recorder;
        this.clock = clock;
    }

    // ---- identity ----

    @ApplicationModuleListener
    void on(UserProvisioned event) {
        recorder.record("user_provisioned:" + event.subject() + ":" + event.occurredAt(),
                AuditEntry.of(null, "identity.user_provisioned", event.subject(),
                        "email=" + event.email(), event.occurredAt()));
    }

    @ApplicationModuleListener
    void on(UserActivated event) {
        recorder.record("user_activated:" + event.subject() + ":" + event.occurredAt(),
                AuditEntry.of(null, "identity.user_activated", event.subject(), null, event.occurredAt()));
    }

    // ---- organization ----

    @ApplicationModuleListener
    void on(OrganizationRegistered event) {
        recorder.record("org_registered:" + event.orgId() + ":" + event.occurredAt(),
                AuditEntry.of(event.orgId(), "organization.registered", event.alias(), null, event.occurredAt()));
    }

    @ApplicationModuleListener
    void on(MembershipCreated event) {
        recorder.record("member_added:" + event.orgId() + ":" + event.subject() + ":" + event.occurredAt(),
                AuditEntry.of(event.orgId(), "organization.member_added", event.subject(),
                        "role=" + event.roleCode(), event.occurredAt()));
    }

    @ApplicationModuleListener
    void on(MembershipRoleChanged event) {
        recorder.record("member_role_changed:" + event.orgId() + ":" + event.subject() + ":" + event.occurredAt(),
                AuditEntry.of(event.orgId(), "organization.member_role_changed", event.subject(),
                        null, event.occurredAt()));
    }

    @ApplicationModuleListener
    void on(MemberRemoved event) {
        recorder.record("member_removed:" + event.orgId() + ":" + event.subject() + ":" + event.occurredAt(),
                AuditEntry.of(event.orgId(), "organization.member_removed", event.subject(),
                        null, event.occurredAt()));
    }

    @ApplicationModuleListener
    void on(RolePermissionsChanged event) {
        recorder.record("role_perms_changed:" + event.orgId() + ":" + event.roleId() + ":" + event.occurredAt(),
                AuditEntry.of(event.orgId(), "organization.role_permissions_changed",
                        event.roleId().toString(), null, event.occurredAt()));
    }

    @ApplicationModuleListener
    void on(OrganizationStatusChanged event) {
        recorder.record("org_status_changed:" + event.orgId() + ":" + event.status() + ":" + event.occurredAt(),
                AuditEntry.of(event.orgId(), "organization.status_changed", null,
                        "status=" + event.status(), event.occurredAt()));
    }

    // ---- settings ----

    @ApplicationModuleListener
    void on(FeatureFlagChanged event) {
        recorder.record("flag_changed:" + event.key() + ":" + event.enabled() + ":" + event.occurredAt(),
                AuditEntry.of(null, "settings.feature_flag_changed", event.key(),
                        "enabled=" + event.enabled(), event.occurredAt()));
    }

    @ApplicationModuleListener
    void on(SettingChanged event) {
        // SettingChanged carries no occurredAt; stamp at record time and dedupe on key+value.
        Instant now = clock.instant();
        recorder.record("setting_changed:" + event.key() + ":" + event.value(),
                AuditEntry.of(null, "settings.changed", event.key(), "value=" + event.value(), now));
    }
}
