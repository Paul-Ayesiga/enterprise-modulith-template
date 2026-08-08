package ug.co.smsone.access.internal;

import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import ug.co.smsone.shared.tenancy.TenantContext;
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

    /**
     * The org whose axis this sweep borrows. It names no organization deliberately: an org that has
     * never been promoted resolves to the shared {@code tenant_pool}, and a UUID in no
     * {@code organization} row can never resolve to anything else — so this IS the pooled schema's axis,
     * spelled with the only vocabulary {@code TenantContext} has. Same constant, same reasoning as
     * {@code MappedSchemaValidator} and {@code WebhookSecretEncryptionMigrator}: one idiom for "the
     * pool", not three.
     *
     * <p>It is the honest shape for this listener specifically, because the grants being deleted belong
     * to every org that blessed the device and the event carries no {@code orgId} to name one (see
     * {@link ug.co.smsone.access.DeviceRevoked}). When silos exist (ADR 0010 Phase 5) this stops being
     * one pass: the loop belongs here, over {@code platform.tenant_placement}, one transaction per home
     * — which is exactly the shape the class note already argues an event buys us.
     */
    private static final java.util.UUID POOLED_TENANT = new java.util.UUID(0L, 0L);

    private final UserDeviceTrustRepository deviceTrust;
    private final org.springframework.transaction.support.TransactionTemplate transactions;

    DeviceTrustRevocationListener(UserDeviceTrustRepository deviceTrust,
            org.springframework.transaction.support.TransactionTemplate transactions) {
        this.deviceTrust = deviceTrust;
        this.transactions = transactions;
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
        TenantContext.runAs(POOLED_TENANT, () -> transactions.executeWithoutResult(tx -> revokeGrants(event)));
    }

    /**
     * A {@code TransactionTemplate} and not a {@code @Transactional} method, because the obvious version
     * of this is broken in a way that passes review: calling an annotated method on {@code this} is a
     * SELF-INVOCATION, so Spring's proxy never sees it, no transaction starts, and the modifying delete
     * silently does nothing. The template needs no proxy, so the transaction actually exists — and it
     * begins INSIDE the pin above, which is the ordering the whole thing depends on.
     */
    private void revokeGrants(DeviceRevoked event) {
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
