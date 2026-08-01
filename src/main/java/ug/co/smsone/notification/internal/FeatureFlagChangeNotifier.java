package ug.co.smsone.notification.internal;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import ug.co.smsone.notification.Notifications;
import ug.co.smsone.settings.FeatureFlagChanged;
import ug.co.smsone.shared.events.EventInbox;

/**
 * Notifies administrators (email + in-app) when a feature flag is toggled — the module's first
 * cross-module event consumer. Idempotent via {@link EventInbox}: at-least-once redelivery of the
 * same change is de-duplicated. The message id is {@code flag:<key>:<state>@<occurredAt>}, derived
 * from business identity since domain events carry no envelope id; {@code occurredAt} is what lets
 * a genuine later re-toggle to the same state notify again while a redelivery of it does not.
 */
@Component
class FeatureFlagChangeNotifier {

    private static final String LISTENER_ID = "notification-flag-change";

    private final Notifications notifications;
    private final EventInbox inbox;

    FeatureFlagChangeNotifier(Notifications notifications, EventInbox inbox) {
        this.notifications = notifications;
        this.inbox = inbox;
    }

    @ApplicationModuleListener
    void on(FeatureFlagChanged event) {
        // occurredAt makes the key unique per change yet stable across redelivery — so a genuine
        // re-toggle still notifies, but an at-least-once redelivery of the same event does not.
        String messageId = "flag:" + event.key() + ":" + event.enabled() + "@" + event.occurredAt();
        if (!inbox.recordIfNew(LISTENER_ID, messageId)) {
            return;
        }
        String state = event.enabled() ? "enabled" : "disabled";
        String subject = "[SMSOne] Feature flag '" + event.key() + "' " + state;
        String body = "The feature flag '" + event.key() + "' is now " + state + ".";
        notifications.notifyAdmins(subject, body);
    }
}
