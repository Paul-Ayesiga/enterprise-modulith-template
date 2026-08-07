package ug.co.smsone.access.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import ug.co.smsone.access.DeviceRevoked;

/**
 * Deletes every organization's trust grant over a device the moment its owner revokes it — the
 * event-driven half of what {@code user_device_trust_device_id_fkey}'s {@code ON DELETE CASCADE} used
 * to do for free, before V53 cut it for crossing the tenant boundary (ADR 0010 §6).
 *
 * <p><b>Why this cannot just be a line in {@code DeviceService.revoke}.</b> It could be, today, and it
 * would even close the async window — but it stops being expressible at Phase 5. The grants live in the
 * TENANT tier, so after promotion one device's grants are spread across every silo that blessed it, and
 * a synchronous {@code delete} on the revoking request's connection reaches exactly one schema. An
 * event is the shape that survives: the same listener runs once per tenant, retried by the outbox, with
 * no connection held open across N schemas. Writing it synchronously now would be a line to delete
 * later and a habit to unlearn — and the window it closes is milliseconds, against a reconciler that
 * bounds the worst case anyway.
 *
 * <p><b>No {@code EventInbox} guard, on purpose.</b> Every other listener in this codebase records the
 * event before acting, because its work is not idempotent. This one's is: deleting rows that are
 * already gone removes nothing. Adding the inbox would make it strictly worse — the inbox marks the
 * event seen BEFORE the work, so a delete that failed after the mark would never be retried, and a
 * grant would survive its device permanently. Idempotent work wants retries, not deduplication.
 *
 * <p>A failure here throws, which is the correct direction: Modulith leaves the publication incomplete
 * and {@code OutboxResubmissionJob} retries it. Swallowing it would leave a revoked device trusted with
 * nothing but a log line to say so, until the nightly reconciler noticed.
 */
@Component
class DeviceTrustRevocationListener {

    private static final Logger log = LoggerFactory.getLogger(DeviceTrustRevocationListener.class);

    private final UserDeviceTrustRepository deviceTrust;

    DeviceTrustRevocationListener(UserDeviceTrustRepository deviceTrust) {
        this.deviceTrust = deviceTrust;
    }

    @ApplicationModuleListener
    void on(DeviceRevoked event) {
        int removed = deviceTrust.revokeEverywhere(event.deviceId());
        if (removed > 0) {
            // Logged at INFO because it is a security-relevant state change with no other trail: the
            // grant row IS the trust (V51 — absence means untrusted), so its deletion leaves nothing
            // behind to inspect. audit_log carries the org's own grant/revoke acts, not this one.
            log.info("Device {} revoked by person {} — removed {} organization trust grant(s)",
                    event.deviceId(), event.personId(), removed);
        }
    }
}
