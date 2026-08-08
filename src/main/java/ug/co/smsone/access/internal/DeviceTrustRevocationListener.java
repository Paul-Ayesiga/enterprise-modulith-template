package ug.co.smsone.access.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import ug.co.smsone.access.DeviceRevoked;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.shared.tenancy.TenantFanOut;
import ug.co.smsone.shared.tenancy.TenantHome;

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
    private final org.springframework.transaction.support.TransactionTemplate transactions;
    private final TenantFanOut fanOut;

    DeviceTrustRevocationListener(UserDeviceTrustRepository deviceTrust,
            org.springframework.transaction.support.TransactionTemplate transactions, TenantFanOut fanOut) {
        this.deviceTrust = deviceTrust;
        this.transactions = transactions;
        this.fanOut = fanOut;
    }

    /**
     * The axis is declared OUTSIDE the transaction, and that ordering is the whole subtlety.
     *
     * <p>{@code @ApplicationModuleListener} bundles {@code @Async} with {@code @Transactional}, so the
     * moment this method is entered a connection has already been borrowed — with no axis, because a
     * pooled listener thread carries none and {@code TenantContext} deliberately does not propagate
     * across threads (ADR 0010 §3.2: the outbox resubmitter has no thread context at all, so an
     * inherited axis would make the immediate and retried deliveries of the same event behave
     * differently). Pinning inside would not help and is refused anyway: {@code TenantContext} throws
     * inside an active transaction precisely because the schema is chosen at borrow, so a pin set after
     * one is a silent no-op. Hence the split — pin, then transact.
     */
    @Async
    @TransactionalEventListener
    void on(DeviceRevoked event) {
        // A TENANT axis, not the platform one this used to take. user_device_trust is tenant-tier
        // (ADR 0010 §2) while user_device — the row this event is about — is the platform's, which is
        // precisely why V53 could not keep a foreign key between them. Through Phase 1 the platform pin
        // was a no-op because both tables lived in one schema; since Phase 2 a platform axis cannot see
        // this table at all, so the delete would fail on every revocation with
        // relation "user_device_trust" does not exist — asynchronously, retried by the outbox, with a
        // revoked device left trusted in the meantime.
        //
        // ONE PASS PER HOME since Phase 5, and this is the sharpest case in the whole fan-out. The
        // grants belong to every org that blessed the device and the event carries no orgId to name one
        // (see DeviceRevoked), so "everywhere" used to mean one schema. After a promotion it meant every
        // schema BUT the promoted tenant's — a revoked device left trusted, indefinitely, in exactly the
        // orgs that were given their own schema, satisfying require_trusted_device with a grant nothing
        // would ever remove. AGENTS §1 names this class of bug ("forget this and revoked devices stay
        // trusted forever") and V53's cut cascade is why nothing else would have caught it.
        //
        // A TransactionTemplate per home and not a @Transactional method, for the reason the obvious
        // version fails review only after it has shipped: calling an annotated method on `this` is a
        // SELF-INVOCATION, Spring's proxy never sees it, no transaction starts, and the modifying delete
        // silently does nothing. The template needs no proxy — and it opens INSIDE each pin, which is
        // the ordering the whole thing depends on.
        TenantFanOut.Fleet fleet = fanOut.fleet();
        int removed = 0;
        for (TenantHome home : fleet.homes()) {
            removed += TenantContext.callAs(home.axis(),
                    () -> transactions.execute(tx -> deviceTrust.revokeEverywhere(event.deviceId())));
        }
        report(event, removed, fleet.withheldHomes());
    }

    /**
     * <b>A home this pass could not enter is a grant that survived its device, so an incomplete pass
     * THROWS.</b>
     *
     * <p>Every other fanned-out caller in this codebase is a scheduled sweep whose next pass is minutes
     * or hours away, so a home withheld by a promotion freeze is simply visited next time and nobody
     * needs to be told. This one is a reaction to an event, and there is no next pass to fall back on —
     * but there IS a retry, and it is the one the class note already relies on: throwing leaves the
     * Modulith publication incomplete and {@code OutboxResubmissionJob} resubmits it after the freeze
     * has lifted. That is free precisely because the work is idempotent (deleting rows that are already
     * gone removes nothing), which is the same property that made the {@code EventInbox} guard the wrong
     * tool here.
     *
     * <p>The alternative — logging and returning — would leave a revoked device satisfying
     * {@code require_trusted_device} in a promoted tenant with nothing but a 03:00 ERROR to say so.
     * AGENTS §1 names exactly that failure ("forget this and revoked devices stay trusted forever"), and
     * a stale trust row is the same class of bug as the device-trust bypass the branch before this one
     * fixed.
     */
    private void report(DeviceRevoked event, int removed, int withheld) {
        if (removed > 0) {
            // Logged at INFO because it is a security-relevant state change with no other trail: the
            // grant row IS the trust (V51 — absence means untrusted), so its deletion leaves nothing
            // behind to inspect. audit_log carries the org's own grant/revoke acts, not this one.
            log.info("Device {} revoked by person {} — removed {} organization trust grant(s)",
                    event.deviceId(), event.personId(), removed);
        }
        if (withheld > 0) {
            throw new IllegalStateException(
                    "device " + event.deviceId() + " was revoked but " + withheld + " tenant home(s) are"
                            + " frozen for a promotion, so any trust grant it holds there is still live."
                            + " Failing so the publication stays incomplete and OutboxResubmissionJob"
                            + " retries once the freeze has lifted — this delete is idempotent, so the"
                            + " retry costs nothing (ADR 0010 §6 hop 0->1).");
        }
    }

}
